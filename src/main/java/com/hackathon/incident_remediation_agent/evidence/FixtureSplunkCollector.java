package com.hackathon.incident_remediation_agent.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.ObjectMapper;

/**
 * Stand-in until the real Splunk search adapter exists. Serves whatever is in
 * {@code /fixtures/log-evidence.json}.
 *
 * <p>The content is a fixture rather than generated text on purpose: log samples are the only thing
 * telling the model <em>where</em> to look, and they end up quoted in a Jira comment and a pull
 * request body. Invented-looking stack traces there would read as real evidence. Edit the fixture
 * with the actual stack trace from the incident you are demonstrating.
 *
 * <p>When the live collector lands, gate both on {@code agent.mode} the way the AI adapters are.
 */
@Component
public class FixtureSplunkCollector implements SplunkCollector {

    private static final Logger log = LoggerFactory.getLogger(FixtureSplunkCollector.class);

    private static final String RESOURCE = "/fixtures/log-evidence.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String searchLink;

    FixtureSplunkCollector(AgentProperties properties) {
        AgentProperties.Splunk splunk = properties.splunk();
        this.searchLink = splunk == null ? null : splunk.searchLink();
    }

    @Override
    public LogEvidence collect(IncidentAlert alert) {
        log.warn("Splunk collector is a fixture; log evidence for {} comes from {}",
            alert.incidentId(), RESOURCE);
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + RESOURCE);
            }
            LogEvidence fixture = this.objectMapper.readValue(stream, LogEvidence.class);
            return fixture.sourceUrl() != null || this.searchLink == null || this.searchLink.isBlank()
                ? fixture
                : new LogEvidence(fixture.errorCount(), fixture.topError(), fixture.samples(),
                    java.net.URI.create(this.searchLink));
        }
        catch (IOException exception) {
            throw new UncheckedIOException("Cannot read fixture " + RESOURCE, exception);
        }
    }
}
