package com.hackathon.incident_remediation_agent.git;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.RepositoryChange;

import tools.jackson.databind.JsonNode;

/**
 * Builds the branch, commit and draft pull request through the GitHub REST API.
 *
 * <p>Nothing is cloned. A commit is assembled out of blobs and a tree, which is why the model
 * returns whole files rather than a diff: there is no working copy to apply a patch to.
 *
 * <p>Writes are refused unless {@code agent.github.push-enabled} is true. The default is false so
 * that running this against a real repository is a deliberate act, not a side effect of starting
 * the application with a token in the environment.
 */
@Component
public class RestGitHubClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(RestGitHubClient.class);

    private static final int MAX_BODY_CHARACTERS = 20_000;
    private static final int MAX_SLUG_CHARACTERS = 40;
    private static final String DEFAULT_FILE_MODE = "100644";

    /**
     * Slugs and branches go into the URI template as literal text, not as template variables: a
     * variable containing {@code /} is encoded to {@code %2F} and every call 404s. Their shape is
     * therefore checked, so nothing from configuration can inject a template expression.
     */
    private static final Pattern SAFE_SLUG =
        Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/-]+");

    private final RestClient restClient;
    private final String branchPrefix;
    private final boolean pushEnabled;

    RestGitHubClient(RestClient.Builder builder, AgentProperties properties) {
        AgentProperties.GitHub github = properties.github();
        this.branchPrefix = trimSlashes(github.branchPrefix());
        this.pushEnabled = github.pushEnabled();
        this.restClient = builder
            .baseUrl(github.apiBaseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + github.token())
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .build();
    }

    @Override
    public FixSubmission submitFix(
        EvidencePack pack,
        FixProposal proposal,
        AgentProperties.RepositoryTarget target,
        List<String> changedFiles
    ) {
        String branch = branchName(pack, proposal);

        if (!this.pushEnabled) {
            log.warn("agent.github.push-enabled is false; not writing to {}. Would have created "
                + "branch {} from {} changing {}",
                target.slug(), branch, target.baseBranch(), changedFiles);
            return new FixSubmission(branch, null, changedFiles, null);
        }

        String baseSha = baseCommitSha(target);
        String baseTreeSha = treeShaOf(target, baseSha);
        Map<String, String> modes = modesFor(target, baseTreeSha, changedFiles);

        List<Map<String, Object>> entries = new ArrayList<>();
        proposal.files().forEach((path, content) -> entries.add(Map.of(
            "path", path,
            "mode", modes.getOrDefault(path, DEFAULT_FILE_MODE),
            "type", "blob",
            "sha", createBlob(target, content))));

        String treeSha = post(target, "/git/trees",
            Map.of("base_tree", baseTreeSha, "tree", entries)).get("sha").asString();

        String commitSha = post(target, "/git/commits", Map.of(
            "message", commitMessage(pack, proposal),
            "tree", treeSha,
            "parents", List.of(baseSha))).get("sha").asString();

        post(target, "/git/refs", Map.of(
            "ref", "refs/heads/" + branch,
            "sha", commitSha));

        DraftPullRequest pullRequest = openDraft(pack, proposal, target, branch, changedFiles);
        log.info("Opened draft pull request {} on {} for {}",
            pullRequest.url(), target.slug(), pack.ticket().key());
        return new FixSubmission(branch, commitSha, changedFiles, pullRequest);
    }

    @Override
    public DraftPullRequest revert(
        EvidencePack pack,
        RepositoryChange change,
        AgentProperties.RepositoryTarget target
    ) {
        if (change == null || change.commitSha() == null) {
            return null;
        }
        JsonNode head = this.restClient.get()
            .uri(api(target, "/commits/" + require(SAFE_REF, change.commitSha(), "commit sha")))
            .retrieve()
            .body(JsonNode.class);

        JsonNode parents = head.path("parents");
        if (parents.isEmpty()) {
            log.warn("{} has no parent; nothing to revert to", change.commitSha());
            return null;
        }
        // The first parent, matching how GitHub diffed this commit in the first place: for a
        // merged pull request that is the branch tip it merged into, so reverting undoes the
        // whole pull request rather than one commit of it.
        String parentSha = parents.get(0).path("sha").asString();

        List<String> paths = new ArrayList<>();
        head.path("files").forEach(file -> {
            String path = file.path("filename").asString(null);
            if (path != null) {
                paths.add(path);
            }
        });
        if (paths.isEmpty()) {
            log.warn("{} changed no files; nothing to revert", change.commitSha());
            return null;
        }

        String branch = "%s/revert-%s".formatted(this.branchPrefix, abbreviate(change.commitSha()));
        if (!this.pushEnabled) {
            log.warn("agent.github.push-enabled is false; not writing to {}. Would have created "
                + "branch {} reverting {} across {}",
                target.slug(), branch, abbreviate(change.commitSha()), paths);
            return null;
        }

        String baseSha = baseCommitSha(target);
        String baseTreeSha = treeShaOf(target, baseSha);
        Map<String, String> modes = modesFor(target, baseTreeSha, paths);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String path : paths) {
            String before = contentAt(target, path, parentSha);
            if (before == null) {
                // The commit added this file, so putting it back means removing it. A null sha is
                // how the tree API expresses a deletion.
                Map<String, Object> deletion = new LinkedHashMap<>();
                deletion.put("path", path);
                deletion.put("mode", modes.getOrDefault(path, DEFAULT_FILE_MODE));
                deletion.put("type", "blob");
                deletion.put("sha", null);
                entries.add(deletion);
            }
            else {
                entries.add(Map.of(
                    "path", path,
                    "mode", modes.getOrDefault(path, DEFAULT_FILE_MODE),
                    "type", "blob",
                    "sha", createBlob(target, before)));
            }
        }

        String treeSha = post(target, "/git/trees",
            Map.of("base_tree", baseTreeSha, "tree", entries)).get("sha").asString();
        String commitSha = post(target, "/git/commits", Map.of(
            "message", revertMessage(change),
            "tree", treeSha,
            "parents", List.of(baseSha))).get("sha").asString();
        post(target, "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", commitSha));

        DraftPullRequest draft = openRevertDraft(pack, change, target, branch, paths);
        log.info("Opened revert draft pull request {} on {} undoing {} for {}",
            draft.url(), target.slug(), abbreviate(change.commitSha()), pack.ticket().key());
        return draft;
    }

    /** @return the file's content at {@code ref}, or null when it did not exist there */
    private String contentAt(AgentProperties.RepositoryTarget target, String path, String ref) {
        try {
            return this.restClient.get()
                .uri("/repos/" + require(SAFE_SLUG, target.slug(), "repository slug")
                    + "/contents/" + require(SAFE_REF, path, "file path") + "?ref=" + ref)
                .accept(MediaType.valueOf("application/vnd.github.raw"))
                .retrieve()
                .body(String.class);
        }
        catch (HttpClientErrorException.NotFound notFound) {
            return null;
        }
    }

    private DraftPullRequest openRevertDraft(
        EvidencePack pack,
        RepositoryChange change,
        AgentProperties.RepositoryTarget target,
        String branch,
        List<String> paths
    ) {
        StringBuilder body = new StringBuilder();
        body.append("Opened by the incident remediation agent for **")
            .append(pack.ticket().key()).append("**.\n\n");
        body.append("Puts every file touched by `").append(abbreviate(change.commitSha()))
            .append("` back to its content at the commit before it.\n\n");
        if (change.lastPullRequest() != null) {
            body.append("Reverts #").append(change.lastPullRequest().number())
                .append(" (merged ").append(change.lastPullRequest().mergedAt()).append(").\n\n");
        }
        body.append("Files:\n");
        paths.forEach(path -> body.append("- `").append(path).append("`\n"));
        body.append("\nThis is a rollback, not a fix. Nothing here diagnoses the incident.\n");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "[%s] Revert %s".formatted(pack.ticket().key(), headline(change)));
        request.put("head", branch);
        request.put("base", target.baseBranch());
        request.put("body", body.toString());
        request.put("draft", true);

        JsonNode response = post(target, "/pulls", request);
        JsonNode draft = response.get("draft");
        if (draft == null || !draft.asBoolean()) {
            throw new IllegalStateException(
                "GitHub created a non-draft pull request on " + target.slug()
                    + "; draft pull requests may be disabled for this repository");
        }
        return new DraftPullRequest(
            response.get("number").asLong(),
            URI.create(response.get("html_url").asString()));
    }

    private static String revertMessage(RepositoryChange change) {
        return "Revert \"%s\"\n\nThis reverts commit %s."
            .formatted(headline(change), change.commitSha());
    }

    private static String headline(RepositoryChange change) {
        if (change.lastPullRequest() != null) {
            return "#%d %s".formatted(
                change.lastPullRequest().number(), change.lastPullRequest().title());
        }
        String message = change.commitMessage();
        return message == null ? abbreviate(change.commitSha()) : message.lines().findFirst().orElse("");
    }

    private static String abbreviate(String sha) {
        return sha == null || sha.length() < 8 ? sha : sha.substring(0, 8);
    }

    private String baseCommitSha(AgentProperties.RepositoryTarget target) {
        JsonNode ref = this.restClient.get()
            .uri(api(target, "/git/ref/heads/" + require(SAFE_REF, target.baseBranch(), "base branch")))
            .retrieve()
            .body(JsonNode.class);
        return ref.get("object").get("sha").asString();
    }

    /**
     * A tree cannot be based on a commit id, so the commit's own tree has to be fetched. Getting
     * this wrong produces a commit that silently deletes every file the tree omits.
     */
    private String treeShaOf(AgentProperties.RepositoryTarget target, String commitSha) {
        JsonNode commit = this.restClient.get()
            .uri(api(target, "/git/commits/" + commitSha))
            .retrieve()
            .body(JsonNode.class);
        return commit.get("tree").get("sha").asString();
    }

    /**
     * Preserves each file's existing mode. Writing {@code 100644} over an executable file would
     * quietly drop its executable bit, which no reviewer would think to look for in a fix.
     */
    private Map<String, String> modesFor(
        AgentProperties.RepositoryTarget target,
        String treeSha,
        List<String> paths
    ) {
        JsonNode tree = this.restClient.get()
            .uri(api(target, "/git/trees/" + treeSha + "?recursive=1"))
            .retrieve()
            .body(JsonNode.class);

        Map<String, String> modes = new HashMap<>();
        JsonNode entries = tree.get("tree");
        if (entries != null) {
            for (JsonNode entry : entries) {
                JsonNode path = entry.get("path");
                JsonNode mode = entry.get("mode");
                if (path != null && mode != null && paths.contains(path.asString())) {
                    modes.put(path.asString(), mode.asString());
                }
            }
        }
        JsonNode truncated = tree.get("truncated");
        if (truncated != null && truncated.asBoolean() && modes.size() < paths.size()) {
            log.warn("Tree listing for {} was truncated; unresolved files default to {}",
                target.slug(), DEFAULT_FILE_MODE);
        }
        return modes;
    }

    private String createBlob(AgentProperties.RepositoryTarget target, String content) {
        return post(target, "/git/blobs",
            Map.of("content", content, "encoding", "utf-8")).get("sha").asString();
    }

    private DraftPullRequest openDraft(
        EvidencePack pack,
        FixProposal proposal,
        AgentProperties.RepositoryTarget target,
        String branch,
        List<String> changedFiles
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "[%s] %s".formatted(pack.ticket().key(), proposal.summary()));
        request.put("head", branch);
        request.put("base", target.baseBranch());
        request.put("body", body(pack, proposal, changedFiles));
        request.put("draft", true);

        JsonNode response = post(target, "/pulls", request);
        JsonNode draft = response.get("draft");
        if (draft == null || !draft.asBoolean()) {
            // Never leave a non-draft pull request standing: it can be reviewed and merged.
            throw new IllegalStateException(
                "GitHub created a non-draft pull request on " + target.slug()
                    + "; draft pull requests may be disabled for this repository");
        }
        return new DraftPullRequest(
            response.get("number").asLong(),
            URI.create(response.get("html_url").asString()));
    }

    private JsonNode post(AgentProperties.RepositoryTarget target, String path, Object body) {
        return this.restClient.post()
            .uri(api(target, path))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
    }

    /** @param path everything after {@code /repos/<owner>/<name>} */
    private static String api(AgentProperties.RepositoryTarget target, String path) {
        return "/repos/" + require(SAFE_SLUG, target.slug(), "repository slug") + path;
    }

    private static String require(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsupported %s: %s".formatted(what, value));
        }
        return value;
    }

    private String branchName(EvidencePack pack, FixProposal proposal) {
        return "%s/%s-%s".formatted(this.branchPrefix, pack.ticket().key(), slug(proposal.summary()));
    }

    private static String commitMessage(EvidencePack pack, FixProposal proposal) {
        return "fix(%s): %s".formatted(pack.ticket().key(), proposal.summary());
    }

    private static String body(EvidencePack pack, FixProposal proposal, List<String> changedFiles) {
        StringBuilder body = new StringBuilder();
        body.append("Opened automatically from PagerDuty incident ")
            .append(pack.alert().incidentId()).append(".\n\n");
        body.append("**Jira:** ").append(pack.ticket().browseUrl()).append('\n');
        body.append("**PagerDuty:** ").append(pack.alert().incidentUrl()).append('\n');
        body.append("**Evidence version:** `").append(pack.evidenceVersion()).append("`\n");
        body.append("**Classification:** ").append(pack.classification()).append("\n\n");

        body.append("## Hypothesis\n\n").append(proposal.hypothesis()).append("\n\n");

        body.append("## Evidence\n\n");
        if (pack.logs() != null) {
            body.append("- Top error: `").append(pack.logs().topError()).append("`\n");
            body.append("- Matching log events: ").append(pack.logs().errorCount()).append('\n');
        }
        if (pack.metrics() != null) {
            body.append("- Error rate before: ").append(pack.metrics().errorRateBefore()).append('\n');
            body.append("- Error rate during: ").append(pack.metrics().errorRateDuring()).append('\n');
            body.append("- Latency changed: ").append(pack.metrics().latencyChanged()).append('\n');
        }
        if (pack.deployment() != null && pack.deployment().version() != null) {
            body.append("- Deployed version: ").append(pack.deployment().version()).append('\n');
        }

        body.append("\n## Changed files\n\n");
        changedFiles.forEach(file -> body.append("- `").append(file).append("`\n"));

        body.append("\n---\n\nThis branch was written by an agent and has not been compiled or "
            + "tested. Review before marking ready.\n");

        String rendered = body.toString();
        return rendered.length() <= MAX_BODY_CHARACTERS
            ? rendered
            : rendered.substring(0, MAX_BODY_CHARACTERS);
    }

    /** Branch-safe fragment of the model's summary. */
    private static String slug(String summary) {
        String cleaned = (summary == null ? "" : summary).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        if (cleaned.isEmpty()) {
            return "fix";
        }
        return cleaned.length() <= MAX_SLUG_CHARACTERS
            ? cleaned
            : cleaned.substring(0, MAX_SLUG_CHARACTERS).replaceAll("-+$", "");
    }

    private static String trimSlashes(String value) {
        return value == null ? "agent/incident" : value.replaceAll("(^/+)|(/+$)", "");
    }
}
