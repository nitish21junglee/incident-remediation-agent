package com.hackathon.incident_remediation_agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

/**
 * Only {@code application_error} opens the door to a code change, so the interesting assertions are
 * the ones that keep it shut.
 */
class EvidenceClassifierTest {

    private static final Instant TRIGGERED = Instant.parse("2026-08-14T02:14:00Z");

    private final EvidenceClassifier classifier = new EvidenceClassifier();

    @Test
    void classifiesARisingApplicationExceptionAsAnApplicationError() {
        String result = classifier.classify(alert(),
            logs("java.lang.NullPointerException: payload is null"),
            metrics(0.001, 0.4, false),
            deployedAt(TRIGGERED.minusSeconds(600)));

        assertThat(result).isEqualTo(EvidenceClassifier.APPLICATION_ERROR);
    }

    /** A release minutes before the incident is enough on its own, even for an unfamiliar error. */
    @Test
    void treatsARecentDeploymentAsEnoughForAnApplicationError() {
        String result = classifier.classify(alert(),
            logs("com.flutter.reward_service.RewardException: settlement rejected"),
            metrics(0.001, 0.4, false),
            deployedAt(TRIGGERED.minusSeconds(300)));

        assertThat(result).isEqualTo(EvidenceClassifier.APPLICATION_ERROR);
    }

    @Test
    void classifiesTransportFailuresAsInfrastructure() {
        String result = classifier.classify(alert(),
            logs("java.net.ConnectException: Connection refused"),
            metrics(0.001, 0.9, false),
            deployedAt(TRIGGERED.minusSeconds(600)));

        assertThat(result).isEqualTo(EvidenceClassifier.INFRASTRUCTURE_OR_DEPENDENCY);
    }

    /** Slower but not failing more is saturation, not a logic bug. */
    @Test
    void classifiesLatencyOnlyChangesAsInfrastructure() {
        String result = classifier.classify(alert(),
            logs("java.lang.NullPointerException: payload is null"),
            metrics(0.2, 0.2, true),
            deployedAt(TRIGGERED.minusSeconds(600)));

        assertThat(result).isEqualTo(EvidenceClassifier.INFRASTRUCTURE_OR_DEPENDENCY);
    }

    /** SignalFx being unreachable zeroes the metrics; that must not reach the AI path. */
    @Test
    void returnsUnknownWhenThereIsNoMetricSignalAtAll() {
        String result = classifier.classify(alert(),
            logs("java.lang.NullPointerException: payload is null"),
            metrics(0, 0, false),
            deployedAt(TRIGGERED.minusSeconds(600)));

        assertThat(result).isEqualTo(EvidenceClassifier.UNKNOWN);
    }

    @Test
    void returnsUnknownWhenTheErrorRateDidNotRise() {
        String result = classifier.classify(alert(),
            logs("java.lang.NullPointerException: payload is null"),
            metrics(0.4, 0.4, false),
            deployedAt(TRIGGERED.minusSeconds(600)));

        assertThat(result).isEqualTo(EvidenceClassifier.UNKNOWN);
    }

    @Test
    void returnsUnknownWithoutATopError() {
        assertThat(classifier.classify(alert(), logs("   "), metrics(0.001, 0.4, false), null))
            .isEqualTo(EvidenceClassifier.UNKNOWN);
        assertThat(classifier.classify(alert(), null, metrics(0.001, 0.4, false), null))
            .isEqualTo(EvidenceClassifier.UNKNOWN);
    }

    /** An old release is not a suspect, so an unrecognised error stays unclassified. */
    @Test
    void returnsUnknownForAnUnrecognisedErrorWithNoRecentDeployment() {
        String result = classifier.classify(alert(),
            logs("com.flutter.reward_service.RewardException: settlement rejected"),
            metrics(0.001, 0.4, false),
            deployedAt(TRIGGERED.minusSeconds(86_400)));

        assertThat(result).isEqualTo(EvidenceClassifier.UNKNOWN);
    }

    private static IncidentAlert alert() {
        return new IncidentAlert("01JDEMOEVENT", "PINCIDENT", "reward-service error rate increased",
            "P2UX5VH", "reward-service", TRIGGERED,
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
    }

    private static LogEvidence logs(String topError) {
        return new LogEvidence(184, topError, List.of("sample"), null);
    }

    private static MetricEvidence metrics(double before, double during, boolean latencyChanged) {
        return new MetricEvidence(before, during, latencyChanged, null, List.of());
    }

    private static DeploymentEvidence deployedAt(Instant when) {
        return new DeploymentEvidence("v1.4.2", "abc123", null, when);
    }
}
