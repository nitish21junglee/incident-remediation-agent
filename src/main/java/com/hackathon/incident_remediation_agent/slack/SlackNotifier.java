package com.hackathon.incident_remediation_agent.slack;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

@Component
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private static final String SLACK_API = "https://slack.com/api/chat.postMessage";
    private static final String REACTIONS_API = "https://slack.com/api/reactions.add";

    private final RestClient restClient;
    private final String botToken;
    private final String webhookUrl;
    private final Map<String, ThreadInfo> threads = new ConcurrentHashMap<>();

    SlackNotifier(RestClient.Builder builder, AgentProperties properties) {
        this.restClient = builder.build();
        AgentProperties.Slack slack = properties.slack();
        this.botToken = slack != null && slack.botToken() != null ? slack.botToken() : "";
        this.webhookUrl = slack != null && slack.webhookUrl() != null ? slack.webhookUrl() : "";
    }

    public void registerThread(String incidentId, String channelId, String threadTs) {
        this.threads.put(incidentId, new ThreadInfo(channelId, threadTs));
    }

    public void acknowledgeMessage(String channelId, String ts) {
        if (botToken.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                "channel", channelId,
                "timestamp", ts,
                "name", "eyes");

            this.restClient.post()
                .uri(URI.create(REACTIONS_API))
                .header("Authorization", "Bearer " + botToken)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        }
        catch (RuntimeException exception) {
            log.warn("Failed to acknowledge Slack message: {}", exception.getMessage());
        }
    }

    public void incidentReceived(IncidentAlert alert) {
        postToThread(alert.incidentId(), List.of(
            section(":rotating_light: *Remediation started* for `%s`".formatted(alert.incidentId()))));
    }

    public void jiraCreated(IncidentAlert alert, String jiraKey, URI jiraBrowseUrl) {
        postToThread(alert.incidentId(), List.of(
            section(":ticket: Jira ticket created: <%s|%s>"
                .formatted(jiraBrowseUrl, jiraKey))));
    }

    public void evidenceCollected(IncidentAlert alert, String classification) {
        postToThread(alert.incidentId(), List.of(
            section(":mag: Evidence collected\nClassification: `%s`"
                .formatted(classification))));
    }

    public void fixProposed(IncidentAlert alert, URI prUrl) {
        postToThread(alert.incidentId(), List.of(
            section(":wrench: Draft PR created\n<%s|View Pull Request>"
                .formatted(prUrl))));
    }

    public void fixRejected(IncidentAlert alert, String reason) {
        postToThread(alert.incidentId(), List.of(
            section(":no_entry: Fix rejected\nReason: %s".formatted(reason))));
    }

    public void noCodeAction(IncidentAlert alert, String classification) {
        postToThread(alert.incidentId(), List.of(
            section(":information_source: No code change proposed\nClassification: `%s`"
                .formatted(classification))));
    }

    public void workflowFailed(IncidentAlert alert, String reason) {
        postToThread(alert.incidentId(), List.of(
            section(":x: Workflow failed\nReason: %s".formatted(reason))));
    }

    private void postToThread(String incidentId, List<Map<String, Object>> blocks) {
        ThreadInfo thread = this.threads.get(incidentId);
        if (thread != null && !botToken.isBlank()) {
            postViaApi(thread.channelId(), thread.threadTs(), blocks);
        } else if (!webhookUrl.isBlank()) {
            postViaWebhook(blocks);
        } else {
            log.debug("No Slack thread or webhook configured; skipping notification");
        }
    }

    private void postViaApi(String channelId, String threadTs, List<Map<String, Object>> blocks) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("channel", channelId);
            body.put("thread_ts", threadTs);
            body.put("blocks", blocks);

            this.restClient.post()
                .uri(URI.create(SLACK_API))
                .header("Authorization", "Bearer " + botToken)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        }
        catch (RuntimeException exception) {
            log.warn("Failed to post Slack thread reply: {}", exception.getMessage());
        }
    }

    private void postViaWebhook(List<Map<String, Object>> blocks) {
        try {
            this.restClient.post()
                .uri(URI.create(webhookUrl))
                .body(Map.of("blocks", blocks))
                .retrieve()
                .toBodilessEntity();
        }
        catch (RuntimeException exception) {
            log.warn("Failed to send Slack webhook: {}", exception.getMessage());
        }
    }

    private static Map<String, Object> section(String markdown) {
        return Map.of("type", "section",
            "text", Map.of("type", "mrkdwn", "text", markdown));
    }

    private static Map<String, Object> fields(String... pairs) {
        List<Map<String, String>> fieldList = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fieldList.add(Map.of("type", "mrkdwn", "text", pairs[i] + "\n" + pairs[i + 1]));
        }
        return Map.of("type", "section", "fields", fieldList);
    }

    private record ThreadInfo(String channelId, String threadTs) {}
}
