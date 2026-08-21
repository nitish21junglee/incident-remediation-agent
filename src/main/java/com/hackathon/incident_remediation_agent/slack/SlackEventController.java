package com.hackathon.incident_remediation_agent.slack;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.workflow.IncidentWorkflow;

import tools.jackson.databind.JsonNode;

@RestController
public class SlackEventController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventController.class);

    private static final Pattern INCIDENT_URL_PATTERN =
        Pattern.compile("https?://[^/|>]+\\.pagerduty\\.com/incidents/(\\w+)");

    private static final Pattern SERVICE_PATTERN =
        Pattern.compile("Service:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern URGENCY_PATTERN =
        Pattern.compile("Urgency:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern INCIDENT_TYPE_PATTERN =
        Pattern.compile("Incident type:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SLACK_LINK_PATTERN =
        Pattern.compile("<[^|>]+\\|([^>]+)>");

    private final IncidentRunStore store;
    private final IncidentWorkflow workflow;
    private final SlackNotifier slackNotifier;
    private final String channelId;

    SlackEventController(IncidentRunStore store, IncidentWorkflow workflow,
        SlackNotifier slackNotifier, AgentProperties properties) {
        this.store = store;
        this.workflow = workflow;
        this.slackNotifier = slackNotifier;
        this.channelId = properties.slack() != null && properties.slack().channelId() != null
            ? properties.slack().channelId() : "";
    }

    @PostMapping("/slack/events")
    ResponseEntity<Object> receive(@RequestBody JsonNode payload) {
        String type = payload.path("type").asString();

        if ("url_verification".equals(type)) {
            return ResponseEntity.ok(Map.of("challenge", payload.path("challenge").asString()));
        }

        if (!"event_callback".equals(type)) {
            return ResponseEntity.ok().build();
        }

        JsonNode event = payload.path("event");
        if (!"message".equals(event.path("type").asString())) {
            return ResponseEntity.ok().build();
        }

        String eventChannel = event.path("channel").asString();
        log.debug("Slack message in channel={}, configured channel={}, subtype={}",
            eventChannel, channelId, event.path("subtype").asString());

        if (!channelId.isBlank() && !channelId.equals(eventChannel)) {
            log.debug("Channel mismatch — ignoring message");
            return ResponseEntity.ok().build();
        }

        String subtype = event.path("subtype").asString();
        if (subtype != null
            && !subtype.isEmpty()
            && !"bot_message".equals(subtype)) {
            log.debug("Ignoring message with subtype={}", subtype);
            return ResponseEntity.ok().build();
        }

        Optional<IncidentAlert> alert = extractIncidentFromMessage(event);
        if (alert.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        IncidentAlert incident = alert.get();
        log.info("Incident detected from Slack: {} ({})", incident.incidentId(), incident.title());

        String messageTs = event.path("ts").asString();
        String channel = event.path("channel").asString();
        slackNotifier.registerThread(incident.incidentId(), channel, messageTs);

        Optional<IncidentRun> started = store.start(incident);
        started.ifPresent(workflow::start);

        return ResponseEntity.ok(Map.of(
            "incidentId", incident.incidentId(),
            "triggered", started.isPresent()));
    }

    private Optional<IncidentAlert> extractIncidentFromMessage(JsonNode event) {
        String text = event.path("text").asString();
        log.debug("Slack event text field: [{}]", text);

        if (text == null || text.isBlank()) {
            text = extractTextFromBlocks(event);
            log.debug("Text from blocks: [{}]", text);
        }
        if (text == null || text.isBlank()) {
            text = extractTextFromAttachments(event);
            log.debug("Text from attachments: [{}]", text);
        }
        if (text == null || text.isBlank()) {
            log.info("Slack message had no extractable text — skipping");
            return Optional.empty();
        }

        String cleanText = stripEmoji(text);
        log.debug("After emoji strip: [{}]", cleanText);

        Matcher urlMatcher = INCIDENT_URL_PATTERN.matcher(text);
        if (urlMatcher.find()) {
            log.info("PagerDuty URL found: {}", urlMatcher.group(0));
            return buildFromUrl(event, text, cleanText, urlMatcher);
        }

        if (looksLikePagerDutyAlert(cleanText)) {
            log.info("Looks like PagerDuty alert text (no URL)");
            return buildFromAlertText(event, cleanText);
        }

        log.info("Message did not match PagerDuty patterns — skipping. Text: [{}]",
            cleanText.length() > 200 ? cleanText.substring(0, 200) + "..." : cleanText);
        return Optional.empty();
    }

    private Optional<IncidentAlert> buildFromUrl(JsonNode event, String text, String cleanText,
        Matcher urlMatcher) {
        String incidentId = urlMatcher.group(1);
        String incidentUrl = urlMatcher.group(0);
        return Optional.of(buildAlert(event, incidentId, extractTitle(text, event),
            extractServiceFromText(cleanText), URI.create(incidentUrl)));
    }

    private Optional<IncidentAlert> buildFromAlertText(JsonNode event, String cleanText) {
        String title = cleanText.lines().findFirst().orElse(cleanText).strip();
        String serviceName = extractServiceFromText(cleanText);
        String incidentId = "SLACK-" + event.path("ts").asString().replace(".", "");
        return Optional.of(buildAlert(event, incidentId, title, serviceName, URI.create("")));
    }

    private IncidentAlert buildAlert(JsonNode event, String incidentId, String title,
        String serviceName, URI incidentUrl) {

        Instant triggeredAt = Instant.now();
        String ts = event.path("ts").asString();
        if (ts != null && !ts.isBlank()) {
            try {
                triggeredAt = Instant.ofEpochSecond(
                    Long.parseLong(ts.contains(".") ? ts.substring(0, ts.indexOf('.')) : ts));
            }
            catch (NumberFormatException ignored) {
            }
        }

        String eventId = event.path("client_msg_id").asString();
        if (eventId == null || eventId.isBlank()) {
            eventId = ts;
        }

        return new IncidentAlert(
            eventId,
            incidentId,
            title,
            "",
            serviceName,
            triggeredAt,
            incidentUrl);
    }

    private boolean looksLikePagerDutyAlert(String text) {
        return INCIDENT_TYPE_PATTERN.matcher(text).find()
            || (URGENCY_PATTERN.matcher(text).find() && SERVICE_PATTERN.matcher(text).find());
    }

    private String extractServiceFromText(String text) {
        Matcher m = SERVICE_PATTERN.matcher(text);
        if (m.find()) {
            String raw = m.group(1).strip();
            // Slack link format: <url|display text> — extract display text
            Matcher linkMatcher = SLACK_LINK_PATTERN.matcher(raw);
            if (linkMatcher.find()) {
                return linkMatcher.group(1).strip();
            }
            return raw;
        }
        return "unknown";
    }

    private String extractTitle(String text, JsonNode event) {
        JsonNode attachments = event.path("attachments");
        if (attachments.isArray()) {
            for (JsonNode att : attachments) {
                String fallback = att.path("fallback").asString();
                if (fallback != null && !fallback.isBlank()) {
                    return fallback;
                }
                String attTitle = att.path("title").asString();
                if (attTitle != null && !attTitle.isBlank()) {
                    return attTitle;
                }
            }
        }
        String firstLine = text.lines().findFirst().orElse(text);
        return firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
    }

    private static String stripEmoji(String text) {
        // Remove Slack-style :emoji_code: patterns (including codes with numbers like :+1:)
        String cleaned = text.replaceAll(":[a-z0-9_+-]+:", "");
        // Remove Unicode emoji characters (emoticons, symbols, pictographs, transport, flags, etc.)
        cleaned = cleaned.replaceAll("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}" +
            "\\x{1F1E0}-\\x{1F1FF}\\x{2600}-\\x{27BF}\\x{2300}-\\x{23FF}\\x{2B50}\\x{2B55}" +
            "\\x{FE0F}\\x{200D}\\x{20E3}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}" +
            "\\x{1FA70}-\\x{1FAFF}\\x{2702}-\\x{27B0}\\x{1F004}\\x{1F0CF}]", "");
        return cleaned.strip();
    }

    private String extractTextFromBlocks(JsonNode event) {
        JsonNode blocks = event.path("blocks");
        if (!blocks.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : blocks) {
            JsonNode textNode = block.path("text").path("text");
            if (!textNode.isMissingNode()) {
                sb.append(textNode.asString()).append('\n');
            }
            JsonNode elements = block.path("elements");
            if (elements.isArray()) {
                for (JsonNode elem : elements) {
                    JsonNode elemText = elem.path("text");
                    if (!elemText.isMissingNode() && elemText.isTextual()) {
                        sb.append(elemText.asString()).append('\n');
                    }
                    JsonNode innerElements = elem.path("elements");
                    if (innerElements.isArray()) {
                        for (JsonNode inner : innerElements) {
                            if ("text".equals(inner.path("type").asString())) {
                                sb.append(inner.path("text").asString()).append(' ');
                            }
                            if ("link".equals(inner.path("type").asString())) {
                                sb.append(inner.path("url").asString()).append(' ');
                            }
                        }
                        sb.append('\n');
                    }
                }
            }
        }
        return sb.toString().strip();
    }

    private String extractTextFromAttachments(JsonNode event) {
        JsonNode attachments = event.path("attachments");
        if (!attachments.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode att : attachments) {
            String pretext = att.path("pretext").asString();
            if (pretext != null && !pretext.isBlank()) {
                sb.append(pretext).append('\n');
            }
            String fallback = att.path("fallback").asString();
            if (fallback != null && !fallback.isBlank()) {
                sb.append(fallback).append('\n');
            }
            String attText = att.path("text").asString();
            if (attText != null && !attText.isBlank()) {
                sb.append(attText).append('\n');
            }
            String title = att.path("title").asString();
            if (title != null && !title.isBlank()) {
                sb.append(title).append('\n');
            }
            String titleLink = att.path("title_link").asString();
            if (titleLink != null && !titleLink.isBlank()) {
                sb.append(titleLink).append('\n');
            }
        }
        return sb.toString().strip();
    }
}
