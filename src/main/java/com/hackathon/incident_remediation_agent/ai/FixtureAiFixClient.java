package com.hackathon.incident_remediation_agent.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

import tools.jackson.databind.ObjectMapper;

/**
 * Serves one deterministic proposal so the demo does not depend on a model provider being
 * reachable. The evidence and repository files are ignored on purpose: fixture mode exists to make
 * the downstream validation and draft-PR path reproducible.
 */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureAiFixClient implements AiFixClient {

    private static final String RESOURCE = "/fixtures/fix-proposal.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public FixProposal propose(EvidencePack pack, Map<String, String> repositoryFiles) {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + RESOURCE);
            }
            return objectMapper.readValue(stream, FixProposal.class);
        }
        catch (IOException exception) {
            throw new UncheckedIOException("Cannot read fixture " + RESOURCE, exception);
        }
    }
}
