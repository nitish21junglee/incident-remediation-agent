package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

import tools.jackson.databind.JsonNode;

/**
 * Reads the base branch's head commit and the last pull request merged into it.
 *
 * <p>{@code GET /commits/{branch}} returns the head commit already diffed against its first
 * parent, so one call answers "what changed last, and against what was stable before it". No
 * checkout and no deployment API are involved, matching how repository files are read.
 *
 * <p>Every failure yields null rather than an exception. This is context that sharpens the
 * investigation; an incident whose repository cannot be reached still has logs, metrics and a
 * stack frame, and must not be abandoned over a missing diff.
 */
@Component
public class GitHubChangeCollector {

    private static final Logger log = LoggerFactory.getLogger(GitHubChangeCollector.class);

    /**
     * Bound on the diff put in front of the model. A release-sized pull request can run to
     * hundreds of kilobytes, which would cost more prompt than the files it is meant to explain.
     * Whole file patches are kept or dropped, never cut, so what survives is still valid diff.
     */
    private static final int MAX_DIFF_CHARACTERS = 20_000;

    private final RestClient restClient;

    GitHubChangeCollector(RestClient.Builder builder, AgentProperties properties) {
        AgentProperties.GitHub github = properties.github();
        this.restClient = builder
            .baseUrl(github.apiBaseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + github.token())
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    /** @return the change, or null when the repository could not be read */
    public RepositoryChange collect(AgentProperties.RepositoryTarget target) {
        try {
            JsonNode head = get("/repos/" + target.slug() + "/commits/" + target.baseBranch());
            if (head == null) {
                log.warn("No head commit for {}@{}; collecting no change",
                    target.slug(), target.baseBranch());
                return null;
            }
            Diff diff = diffOf(head);
            RepositoryChange change = new RepositoryChange(
                text(head, "sha"),
                text(head.path("commit"), "message"),
                instant(head.path("commit").path("author"), "date"),
                diff.patches(),
                diff.dropped(),
                lastPullRequest(target),
                null);
            log.info("Head of {}@{} is {} ({} chars of diff, {} file patches dropped)",
                target.slug(), target.baseBranch(), abbreviate(change.commitSha()),
                diff.patches().length(), diff.dropped());
            return change;
        }
        catch (RuntimeException exception) {
            log.warn("Could not read recent changes for {}; continuing without them",
                target.slug(), exception);
            return null;
        }
    }

    /**
     * The most recently merged pull request, which is the closest thing this repository has to a
     * record of what went live and when. Closed-but-unmerged entries are skipped: they changed
     * nothing on the branch.
     */
    private RepositoryChange.PullRequest lastPullRequest(AgentProperties.RepositoryTarget target) {
        JsonNode pulls = get("/repos/" + target.slug() + "/pulls?state=closed&base="
            + target.baseBranch() + "&sort=updated&direction=desc&per_page=10");
        if (pulls == null || !pulls.isArray()) {
            return null;
        }
        for (JsonNode pull : pulls) {
            Instant mergedAt = instant(pull, "merged_at");
            if (mergedAt == null) {
                continue;
            }
            return new RepositoryChange.PullRequest(
                pull.path("number").asInt(),
                text(pull, "title"),
                uri(pull, "html_url"),
                mergedAt);
        }
        return null;
    }

    /** Concatenates each file's patch, dropping whole patches once the budget is spent. */
    private static Diff diffOf(JsonNode head) {
        StringBuilder patches = new StringBuilder();
        int dropped = 0;
        for (JsonNode file : head.path("files")) {
            String patch = text(file, "patch");
            String path = text(file, "filename");
            if (patch == null || path == null) {
                continue;
            }
            if (patches.length() + patch.length() > MAX_DIFF_CHARACTERS) {
                dropped++;
                continue;
            }
            patches.append("--- ").append(path).append('\n').append(patch).append("\n\n");
        }
        return new Diff(patches.toString().strip(), dropped);
    }

    private JsonNode get(String uri) {
        return this.restClient.get().uri(uri).retrieve().body(JsonNode.class);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private static URI uri(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : URI.create(value);
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String abbreviate(String sha) {
        return sha == null || sha.length() < 8 ? sha : sha.substring(0, 8);
    }

    private record Diff(String patches, int dropped) {}
}
