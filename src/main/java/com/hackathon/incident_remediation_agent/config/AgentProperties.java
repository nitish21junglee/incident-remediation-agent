package com.hackathon.incident_remediation_agent.config;

import java.time.Instant;
import java.util.List;

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
    OpenAi openai,
    GitHub github
) {

    public record Slack(String botToken, String signingSecret, String channelId) {}

    public record Jira(String baseUrl, String email, String apiToken, String projectKey, String issueType) {}

    public record Splunk(String baseUrl, String token, String query, String searchLink) {}

    public record SignalFx(String realm, String token, String errorProgram, String latencyProgram, String dashboardLink) {}

    public record Deployment(String version, String commitSha, String commitUrl, Instant deployedAt) {}

    public record OpenAi(String baseUrl, String apiKey, String model) {}

    public record GitHub(
        String apiBaseUrl,
        String token,
        String repository,
        String baseBranch,
        String localRepositoryPath,
        String branchPrefix,
        List<String> contextFiles,
        List<String> protectedPaths,
        List<String> validationCommand
    ) {}
}
