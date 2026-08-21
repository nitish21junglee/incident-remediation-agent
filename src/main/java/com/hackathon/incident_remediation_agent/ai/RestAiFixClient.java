package com.hackathon.incident_remediation_agent.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.RepositoryChange;
import com.hackathon.incident_remediation_agent.evidence.SignalFxExport;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks an OpenAI-compatible model for a hypothesis and the full content of the files it would
 * change.
 *
 * <p>The model receives evidence text and file text. It gets no credentials, no tools and no
 * network reach of its own: everything it returns is data that this application then gates and
 * writes. Whole files are requested rather than a diff because there is no checkout to apply a
 * patch to, and because model-generated hunk headers fail for reasons unrelated to the fix.
 *
 * <p>Candidate models are tried in order. Gemini's free tier rate-limits routinely, and a
 * rate-limited first choice should cost the run a retry, not the investigation.
 */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "live")
public class RestAiFixClient implements AiFixClient {

    private static final Logger log = LoggerFactory.getLogger(RestAiFixClient.class);

    private static final String SYSTEM_PROMPT = """
        You are an on-call engineer triaging a production incident.

        You are given incident evidence and the complete current content of a small number of \
        source files. Decide whether the evidence points at a bug in these files that you can fix.

        Reply with JSON only, matching this shape exactly:
        {
          "probableFix": true | false,
          "hypothesis": "what is failing and why, in two or three sentences",
          "summary": "imperative one-line description of the change, under 60 characters",
          "files": { "<path exactly as given>": "<complete new content of that file>" }
        }

        Rules:
        - Set probableFix to false and use an empty files object unless the evidence clearly \
        identifies a fault in the files you were given. Guessing is worse than declining.
        - Only ever use paths that appear in the provided files. Never invent a path.
        - Return each changed file in full, byte for byte, including every line you did not change. \
        Do not abbreviate, do not elide with comments, do not return a diff.
        - Change as little as possible. Do not reformat, rename, reorder imports, or tidy \
        unrelated code.
        """;

    /** Trailing tokens are ignored: the reply is the first value, the rest is prose. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private final RestClient restClient;
    private final AiModelSelector models;

    RestAiFixClient(RestClient.Builder builder, AgentProperties properties, AiModelSelector models) {
        AgentProperties.Ai ai = properties.ai();
        this.models = models;
        this.restClient = builder
            .baseUrl(ai.baseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ai.apiKey())
            .build();
    }

    @Override
    public FixProposal propose(EvidencePack pack, Map<String, String> repositoryFiles) {
        List<String> candidates = this.models.candidatesFor(AiTask.FIX_PROPOSAL);
        String prompt = userPrompt(pack, repositoryFiles);
        RuntimeException lastFailure = null;

        for (String model : candidates) {
            try {
                return parse(complete(model, prompt));
            }
            catch (RuntimeException exception) {
                log.warn("Model {} failed to produce a fix proposal for {}; trying the next "
                    + "candidate", model, pack.ticket().key(), exception);
                lastFailure = exception;
            }
        }
        throw new IllegalStateException(
            "Every candidate model failed for " + pack.ticket().key(), lastFailure);
    }

    private String complete(String model, String prompt) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user", "content", prompt)));
        request.put("response_format", Map.of("type", "json_object"));
        request.put("temperature", 0);

        JsonNode response = this.restClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(JsonNode.class);

        JsonNode choices = response == null ? null : response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("Model " + model + " returned no choices");
        }
        JsonNode content = choices.get(0).get("message").get("content");
        if (content == null || content.asString().isBlank()) {
            throw new IllegalStateException("Model " + model + " returned empty content");
        }
        return content.asString();
    }

    private FixProposal parse(String content) {
        JsonNode root = this.objectMapper.readTree(stripOpeningFence(content));
        if (!root.isObject()) {
            throw new IllegalStateException("Model reply held no JSON object");
        }

        JsonNode probableFix = root.get("probableFix");
        if (probableFix == null || !probableFix.asBoolean()) {
            return new FixProposal(false, text(root, "hypothesis"), text(root, "summary"), Map.of());
        }

        Map<String, String> files = new LinkedHashMap<>();
        JsonNode fileNode = root.get("files");
        if (fileNode != null) {
            fileNode.propertyNames().forEach(path -> {
                JsonNode value = fileNode.get(path);
                if (value != null && value.isString()) {
                    files.put(path, value.asString());
                }
            });
        }
        return new FixProposal(true, text(root, "hypothesis"), text(root, "summary"),
            Map.copyOf(files));
    }

    /**
     * Models often wrap JSON in a fenced block despite being asked for JSON only. Only the opening
     * fence has to go: the closing one, and any explanation the model adds after it, are trailing
     * tokens the parser ignores. Searching for the closing fence instead picks the wrong one as
     * soon as the explanation contains a fenced block of its own.
     */
    private static String stripOpeningFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        return firstNewline < 0 ? trimmed : trimmed.substring(firstNewline + 1);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || !value.isString() ? "" : value.asString();
    }

    /**
     * One line per SignalFlow program. The raw points stay in Mongo: hundreds of timestamped
     * floats per series would outweigh the source files without telling the model anything the
     * aggregates do not.
     */
    private static void appendPrograms(StringBuilder prompt, List<SignalFxExport> exports) {
        if (exports == null || exports.isEmpty()) {
            return;
        }
        prompt.append("\nSignalFx programs, aggregated either side of the trigger:\n");
        for (SignalFxExport export : exports) {
            prompt.append("- ").append(export.program())
                .append(" [").append(export.filter()).append("]: ");
            if (export.error() != null) {
                prompt.append("unavailable, ").append(export.error()).append('\n');
                continue;
            }
            prompt.append(export.pointCount()).append(" points")
                .append("; before ").append(describe(export.before()))
                .append("; during ").append(describe(export.during()))
                .append('\n');
        }
    }

    private static String describe(SignalFxExport.Aggregate aggregate) {
        return "sum=%s mean=%s max=%s".formatted(
            number(aggregate.sum()), number(aggregate.mean()), number(aggregate.max()));
    }

    /** Whole numbers read as counts; anything else is capped so a raw double cannot fill a line. */
    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return value == Math.rint(value)
            ? String.valueOf((long) value)
            : String.format(Locale.ROOT, "%.3f", value);
    }

    /**
     * The head commit diffed against its first parent, so the model sees what changed most
     * recently rather than having to find the fault in a file that is mostly unchanged. The full
     * files still follow, because the reply has to contain their complete new content.
     */
    private static void appendRecentChange(StringBuilder prompt, RepositoryChange change) {
        if (change == null) {
            return;
        }
        prompt.append("## Recent change\n\n");
        prompt.append("This is the newest commit on the branch, diffed against the commit before "
            + "it. Treat the earlier commit as the last known good state.\n\n");
        prompt.append("Commit: ").append(change.commitSha()).append('\n');
        if (change.commitMessage() != null) {
            prompt.append("Message: ")
                .append(change.commitMessage().lines().findFirst().orElse("")).append('\n');
        }
        prompt.append("Committed: ").append(change.committedAt()).append('\n');
        RepositoryChange.PullRequest pull = change.lastPullRequest();
        if (pull != null) {
            prompt.append("Last merged pull request: #").append(pull.number()).append(' ')
                .append(pull.title()).append(", merged ").append(pull.mergedAt()).append('\n');
        }
        if (change.truncatedFiles() > 0) {
            prompt.append(change.truncatedFiles())
                .append(" further changed file(s) omitted, the diff was too large\n");
        }
        if (change.diff() != null && !change.diff().isBlank()) {
            prompt.append("\n```diff\n").append(change.diff()).append("\n```\n");
        }
        prompt.append('\n');
    }

    private static String userPrompt(EvidencePack pack, Map<String, String> repositoryFiles) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## Incident\n\n");
        prompt.append("Title: ").append(pack.alert().title()).append('\n');
        prompt.append("Service: ").append(pack.alert().serviceName()).append('\n');
        prompt.append("Triggered: ").append(pack.alert().triggeredAt()).append('\n');
        prompt.append("Classification: ").append(pack.classification()).append("\n\n");

        if (pack.logs() != null) {
            prompt.append("## Logs\n\n");
            prompt.append("Matching events: ").append(pack.logs().errorCount()).append('\n');
            prompt.append("Top error: ").append(pack.logs().topError()).append("\n\n");
            if (pack.logs().samples() != null && !pack.logs().samples().isEmpty()) {
                prompt.append("Samples:\n```\n");
                pack.logs().samples().forEach(sample -> prompt.append(sample).append('\n'));
                prompt.append("```\n\n");
            }
        }

        if (pack.metrics() != null) {
            prompt.append("## Metrics\n\n");
            prompt.append("Error rate before: ").append(pack.metrics().errorRateBefore()).append('\n');
            prompt.append("Error rate during: ").append(pack.metrics().errorRateDuring()).append('\n');
            prompt.append("Latency changed: ").append(pack.metrics().latencyChanged()).append('\n');
            appendPrograms(prompt, pack.metrics().exports());
            prompt.append('\n');
        }

        if (pack.deployment() != null && pack.deployment().version() != null) {
            prompt.append("## Current deployment\n\n");
            prompt.append("Version: ").append(pack.deployment().version()).append('\n');
            prompt.append("Commit: ").append(pack.deployment().commitSha()).append('\n');
            prompt.append("Deployed at: ").append(pack.deployment().deployedAt()).append("\n\n");
        }

        appendRecentChange(prompt, pack.change());

        prompt.append("## Files\n\n");
        List<String> paths = new ArrayList<>(repositoryFiles.keySet());
        for (String path : paths) {
            prompt.append("### ").append(path).append("\n\n```\n")
                .append(repositoryFiles.get(path)).append("\n```\n\n");
        }
        return prompt.toString();
    }
}
