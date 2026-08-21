package com.hackathon.incident_remediation_agent.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

/**
 * Reads the operator-listed repository files over the GitHub Contents API.
 *
 * <p>This is the only place repository content enters a prompt, so it is where "only configured
 * files leave the machine" is enforced. Nothing is cloned: the agent never holds a working tree, so
 * there is no checkout to go stale and no build of someone else's repository to run.
 *
 * <p>The raw media type is requested deliberately. The default JSON representation returns base64
 * with embedded newlines, which {@code Base64.getDecoder()} rejects.
 */
@Component
public class GitHubContextReader {

    private static final Logger log = LoggerFactory.getLogger(GitHubContextReader.class);

    private static final int MAX_TOTAL_CHARACTERS = 100_000;

    /**
     * Slugs, refs and paths are interpolated into the URI template as literal text rather than as
     * template variables, because a template variable containing {@code /} is encoded to
     * {@code %2F} and GitHub then returns 404. That makes their shape part of the contract: no
     * braces, so nothing can inject a template expression.
     */
    private static final Pattern SAFE_SLUG =
        Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._/-]+");

    private final RestClient restClient;

    GitHubContextReader(RestClient.Builder builder, AgentProperties properties) {
        AgentProperties.GitHub github = properties.github();
        this.restClient = builder
            .baseUrl(github.apiBaseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + github.token())
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    /**
     * Paths are candidates derived from the incident, not a curated list, so two things that would
     * be operator error for a configured file are routine here and must not end the run: a path
     * that is not on the branch, and a path that would take the prompt over budget. Both are
     * skipped and logged. Anything the model is not shown it cannot be asked to rewrite, so a
     * short read costs the investigation depth, never safety.
     *
     * @param paths repository-relative paths, in the order they should reach the prompt
     * @return insertion-ordered map of path to file content, which may be smaller than {@code paths}
     * @throws IllegalArgumentException on an absolute or traversing path, or one outside the
     *                                  repository's writable paths
     */
    public Map<String, String> read(AgentProperties.RepositoryTarget target, List<String> paths) {
        Map<String, String> files = new LinkedHashMap<>();
        int total = 0;

        for (String candidate : paths) {
            reject(candidate, target);
            String content = fetch(target, candidate);
            if (content == null) {
                continue;
            }
            if (total + content.length() > MAX_TOTAL_CHARACTERS) {
                log.info("Skipping {} and everything after it: {} characters would take the "
                    + "context over the {} character budget", candidate, content.length(),
                    MAX_TOTAL_CHARACTERS);
                break;
            }
            total += content.length();
            files.put(candidate, content);
        }
        return files;
    }

    /**
     * The API resolves paths server-side, so traversal cannot escape onto our filesystem. It is
     * still refused: a path outside the configured list must not reach the request at all, because
     * the same list is what limits which files may later be rewritten.
     */
    private static void reject(String candidate, AgentProperties.RepositoryTarget target) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Context file path must not be blank");
        }
        if (candidate.startsWith("/")) {
            throw new IllegalArgumentException("Context file must be repository-relative: " + candidate);
        }
        if (candidate.contains("..")) {
            throw new IllegalArgumentException("Context file must not traverse: " + candidate);
        }
        if (!SAFE_PATH.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Context file path has unsupported characters: "
                + candidate);
        }
        List<String> writable = target.writablePaths() == null ? List.of() : target.writablePaths();
        if (writable.stream().noneMatch(candidate::startsWith)) {
            throw new IllegalArgumentException(
                "Context file is outside the repository's writable paths: " + candidate);
        }
    }

    private String fetch(AgentProperties.RepositoryTarget target, String path) {
        String slug = require(SAFE_SLUG, target.slug(), "repository slug");
        String ref = require(SAFE_PATH, target.baseBranch(), "base branch");

        try {
            return this.restClient.get()
                .uri("/repos/" + slug + "/contents/" + path + "?ref=" + ref)
                .accept(MediaType.valueOf("application/vnd.github.raw"))
                .retrieve()
                .body(String.class);
        }
        catch (HttpClientErrorException.NotFound notFound) {
            // A frame can name a class that is not in this repository at all, or not on this
            // branch. Expected, so skip the file rather than ending the investigation.
            log.info("Context file {} is not on {} in {}; skipping it", path, ref, slug);
            return null;
        }
    }

    private static String require(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsupported %s: %s".formatted(what, value));
        }
        return value;
    }
}
