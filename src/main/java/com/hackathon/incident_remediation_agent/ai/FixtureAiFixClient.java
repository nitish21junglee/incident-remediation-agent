package com.hackathon.incident_remediation_agent.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves one deterministic proposal so the demo does not depend on a model provider being
 * reachable.
 *
 * <p>The hypothesis and summary come from {@code /fixtures/fix-proposal.json}, but the changed file
 * is derived from whichever files were actually read: it returns the first one with a marker comment
 * appended. That is what makes fixture mode exercise the real gates — the path is genuinely in the
 * allowlist and the content is genuinely a modified version of the current file — against any
 * configured repository, without inventing a fix that could be mistaken for a real one.
 */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureAiFixClient implements AiFixClient {

    private static final String RESOURCE = "/fixtures/fix-proposal.json";

    private static final String MARKER =
        "// Added by the incident remediation agent in fixture mode. Not a real fix.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public FixProposal propose(EvidencePack pack, Map<String, String> repositoryFiles) {
        JsonNode fixture = read();
        boolean probableFix = fixture.get("probableFix").asBoolean();
        String hypothesis = fixture.get("hypothesis").asString();
        String summary = fixture.get("summary").asString();

        if (!probableFix || repositoryFiles == null || repositoryFiles.isEmpty()) {
            return new FixProposal(false, hypothesis, summary, Map.of());
        }

        Map.Entry<String, String> first = repositoryFiles.entrySet().iterator().next();
        return new FixProposal(true, hypothesis, summary,
            Map.of(first.getKey(), first.getValue() + "\n" + MARKER + "\n"));
    }

    private JsonNode read() {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + RESOURCE);
            }
            return this.objectMapper.readTree(stream);
        }
        catch (IOException exception) {
            throw new UncheckedIOException("Cannot read fixture " + RESOURCE, exception);
        }
    }
}
