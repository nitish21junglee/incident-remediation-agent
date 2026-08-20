package com.hackathon.incident_remediation_agent.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(
    String mode,
    String serviceId,
    Jira jira,
    Splunk splunk,
    SignalFx signalfx,
    Deployment deployment,
    Ai ai,
    GitHub github
) {

    public record Jira(String baseUrl, String email, String apiToken, String projectKey, String issueType) {}

    public record Splunk(String baseUrl, String token, String query, String searchLink) {}

    public record SignalFx(String realm, String token, String errorProgram, String latencyProgram, String dashboardLink) {}

    public record Deployment(String version, String commitSha, String commitUrl, Instant deployedAt) {}

    /**
     * @param defaultModels ordered fallback chain used when a task has no explicit entry
     * @param models        task key (for example {@code fix-proposal}) to its ordered chain
     */
    public record Ai(
        String baseUrl,
        String apiKey,
        List<String> defaultModels,
        Map<String, List<String>> models
    ) {}

    /**
     * @param repositories       short repository name to where it lives and how to validate it.
     *                           This doubles as an allowlist: only names here can ever be cloned,
     *                           patched or pushed.
     * @param serviceRepositories PagerDuty service id to the short repository name that owns it
     */
    public record GitHub(
        String apiBaseUrl,
        String token,
        String branchPrefix,
        List<String> protectedPaths,
        Map<String, RepositoryTarget> repositories,
        Map<String, String> serviceRepositories
    ) {}

    /**
     * @param slug      the {@code owner/name} used against the GitHub API
     * @param directory local checkout the workspace clones from
     */
    public record RepositoryTarget(
        String slug,
        String baseBranch,
        String directory,
        List<String> contextFiles,
        List<String> validationCommand
    ) {}
}
