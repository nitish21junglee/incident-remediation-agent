package com.hackathon.incident_remediation_agent.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(
    String mode,
    String serviceId,
    Slack slack,
    Jira jira,
    Splunk splunk,
    SignalFx signalfx,
    Deployment deployment,
    Ai ai,
    GitHub github
) {

    public record Slack(String botToken, String signingSecret, String channelId) {}

    public record Jira(String baseUrl, String email, String apiToken, String projectKey, String issueType) {}

    public record Splunk(String baseUrl, String token, String query, String searchLink) {}

    /**
     * @param defaultService {@code service.name} to query when the incident's own service name does
     *                       not match a known {@code SignalFxService}. A sandbox PagerDuty service
     *                       rarely carries the production name, so without this the demo collects
     *                       no metrics at all.
     */
    public record SignalFx(
        String realm,
        String token,
        String errorProgram,
        String latencyProgram,
        String dashboardLink,
        String defaultService
    ) {}

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
     * @param pushEnabled         when false the agent stops after the gates and reports what it
     *                            would have done. Nothing is written to GitHub. Default false, so a
     *                            misconfigured run cannot create a branch or a pull request.
     * @param maxChangedFiles     upper bound on files one proposal may touch
     * @param repositories        short repository name to where it lives. This doubles as an
     *                            allowlist: only names here can ever be read or written.
     * @param serviceRepositories PagerDuty service id to the short repository name that owns it
     */
    public record GitHub(
        String apiBaseUrl,
        String token,
        String branchPrefix,
        boolean pushEnabled,
        int maxChangedFiles,
        List<String> protectedPaths,
        Map<String, RepositoryTarget> repositories,
        Map<String, String> serviceRepositories
    ) {}

    /**
     * @param slug          the {@code owner/name} used against the GitHub API
     * @param baseBranch    branch the fix branch is cut from and the pull request targets
     * @param sourceRoots   repository-relative directories where package hierarchies start, for
     *                      example {@code src/main/java/}. Stated rather than guessed: a stack
     *                      frame carries a package, and only the layout says where that package
     *                      begins on disk.
     * @param writablePaths repository-relative path prefixes that may be read and may be
     *                      rewritten. Prefixes rather than exact files, because which file is at
     *                      fault is decided per incident from the stack frames in the evidence;
     *                      this bounds where that decision is allowed to land.
     */
    public record RepositoryTarget(
        String slug,
        String baseBranch,
        List<String> sourceRoots,
        List<String> writablePaths
    ) {}
}
