package com.hackathon.incident_remediation_agent.evidence.signalfx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.evidence.SignalFxExport;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

/**
 * The API only accepts one window, so before-and-after is derived by splitting the returned points
 * at the incident's trigger time. That split, and the decision to give up rather than guess when
 * SignalFx is unavailable, are what these tests pin down.
 */
@ExtendWith(OutputCaptureExtension.class)
class SignalFlowSignalFxCollectorTest {

    private SignalFxSignalFlowClient client;

    @BeforeEach
    void setUp() {
        this.client = mock(SignalFxSignalFlowClient.class);
    }

    @Test
    void splitsPointsAtTheTriggerTimeToCompareBeforeWithDuring() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        // 1 error in 100 requests before, 40 in 100 after.
        stub(SignalFxProgram.ERROR_COUNT, counts(triggered, 1, 40));
        stub(SignalFxProgram.REQUEST_COUNT, counts(triggered, 100, 100));
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");

        MetricEvidence metrics = collector().collect(alert("reward-service", triggered));

        assertThat(metrics.errorRateBefore()).isEqualTo(0.01);
        assertThat(metrics.errorRateDuring()).isEqualTo(0.40);
        assertThat(metrics.latencyChanged()).isFalse();
        assertThat(metrics.dashboardUrl())
            .isEqualTo(URI.create("https://app.eu0.signalfx.com/#/dashboard/demo"));
    }

    @Test
    void reportsLatencyChangedWhenTheMeanRisesSharply() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, "[]");
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, """
            [{"timestampMs":%d,"value":100.0,"label":"C"},
             {"timestampMs":%d,"value":900.0,"label":"C"}]"""
            .formatted(triggered.minusSeconds(120).toEpochMilli(),
                triggered.plusSeconds(120).toEpochMilli()));

        assertThat(collector().collect(alert("reward-service", triggered)).latencyChanged()).isTrue();
    }

    /** The disabled A and B streams are raw counts; averaging them in would corrupt the result. */
    @Test
    void ignoresTheIntermediateStreamsOfTheLatencyProgram() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, "[]");
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, """
            [{"timestampMs":%d,"value":100.0,"label":"C"},
             {"timestampMs":%d,"value":1.0,"label":"A"},
             {"timestampMs":%d,"value":101.0,"label":"C"}]"""
            .formatted(triggered.minusSeconds(120).toEpochMilli(),
                triggered.plusSeconds(60).toEpochMilli(),
                triggered.plusSeconds(120).toEpochMilli()));

        assertThat(collector().collect(alert("reward-service", triggered)).latencyChanged()).isFalse();
    }

    @Test
    void fallsBackToTheConfiguredServiceWhenTheIncidentNameIsUnknown() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, "[]");
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");

        collector().collect(alert("some-sandbox-service", triggered));

        verify(this.client).query(eq(SignalFxProgram.ERROR_COUNT), eq(SignalFxService.REWARD),
            any(), any());
    }

    @Test
    void mapsTheDarsRepositoryNameToTheRewardSignalFxService() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, "[]");
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");

        collector("leaderboard-service").collect(alert("darsrftp-service", triggered));

        verify(this.client).query(eq(SignalFxProgram.ERROR_COUNT), eq(SignalFxService.REWARD),
            any(), any());
    }

    @Test
    void logsTheRawSignalFxResponse(CapturedOutput output) {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        String raw = counts(triggered, 1, 40);
        stub(SignalFxProgram.ERROR_COUNT, raw);
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");

        collector().collect(alert("darsrftp-service", triggered));

        assertThat(output).contains(
            "SignalFx ERROR_COUNT response for filter('service.name', 'reward-service'): " + raw);
    }

    /** A bare name should still match; PagerDuty rarely carries the "-service" suffix. */
    @Test
    void matchesAServiceNameWithoutTheSuffix() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, "[]");
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");

        collector().collect(alert("leaderboard", triggered));

        verify(this.client).query(eq(SignalFxProgram.ERROR_COUNT), eq(SignalFxService.LEADERBOARD),
            any(), any());
    }

    /**
     * Zeroed metrics make the classifier return unknown, which stops the AI path. Losing SignalFx
     * must fail towards proposing nothing.
     */
    @Test
    void returnsZeroedMetricsWhenTheQueryFails() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        when(this.client.query(any(SignalFxProgram.class), any(), any(), any()))
            .thenThrow(new SignalFxQueryException("SignalFlow API returned status 401"));

        MetricEvidence metrics = collector().collect(alert("reward-service", triggered));

        assertThat(metrics.errorRateBefore()).isZero();
        assertThat(metrics.errorRateDuring()).isZero();
        assertThat(metrics.latencyChanged()).isFalse();
    }

    @Test
    void collectsNothingWhenNoServiceCanBeResolved() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));

        MetricEvidence metrics = collector(null).collect(alert("mystery", triggered));

        assertThat(metrics.errorRateDuring()).isZero();
        verify(this.client, org.mockito.Mockito.never())
            .query(any(SignalFxProgram.class), any(), any(), any());
    }

    @Test
    void runsEveryProgramAndExportsEachOne() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, counts(triggered, 1, 40));
        stub(SignalFxProgram.REQUEST_COUNT, counts(triggered, 100, 100));
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");
        stubByNamespace(SignalFxProgram.CPU_UTILIZATION, counts(triggered, 12, 74));
        stubByNamespace(SignalFxProgram.MEMORY_UTILIZATION, counts(triggered, 40, 41));

        MetricEvidence metrics = collector().collect(alert("reward-service", triggered));

        assertThat(metrics.exports()).extracting(SignalFxExport::program).containsExactly(
            "ERROR_COUNT", "REQUEST_COUNT", "LATENCY_BY_URI",
            "CPU_UTILIZATION", "MEMORY_UTILIZATION");
        // Container metrics carry no service.name, so they are scoped by namespace instead.
        assertThat(metrics.exports()).filteredOn(e -> e.program().equals("CPU_UTILIZATION"))
            .singleElement()
            .satisfies(cpu -> {
                assertThat(cpu.filter()).isEqualTo("filter('k8s.namespace.name', 'darsrftp-service-dev')");
                assertThat(cpu.before().max()).isEqualTo(12.0);
                assertThat(cpu.during().max()).isEqualTo(74.0);
            });
        verify(this.client).queryByK8sNamespace(
            eq(SignalFxProgram.MEMORY_UTILIZATION), eq(SignalFxService.REWARD), any(), any());
    }

    /** The raw points go to Mongo, so they have to survive collection verbatim. */
    @Test
    void keepsTheRawPointsOnTheExport() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        String raw = counts(triggered, 1, 40);
        stub(SignalFxProgram.ERROR_COUNT, raw);
        stub(SignalFxProgram.REQUEST_COUNT, "[]");
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");
        stubByNamespace(SignalFxProgram.CPU_UTILIZATION, "[]");
        stubByNamespace(SignalFxProgram.MEMORY_UTILIZATION, "[]");

        MetricEvidence metrics = collector().collect(alert("reward-service", triggered));

        assertThat(metrics.exports().get(0).rawPoints()).isEqualTo(raw);
        assertThat(metrics.exports().get(0).pointCount()).isEqualTo(2);
    }

    /**
     * The namespace is a second guess at what the service is called, so these are the programs
     * most likely to fail. Losing them must not cost the error rate already collected.
     */
    @Test
    void keepsTheErrorRateWhenAContainerProgramFails() {
        Instant triggered = Instant.now().minus(Duration.ofMinutes(10));
        stub(SignalFxProgram.ERROR_COUNT, counts(triggered, 1, 40));
        stub(SignalFxProgram.REQUEST_COUNT, counts(triggered, 100, 100));
        stub(SignalFxProgram.LATENCY_BY_URI, "[]");
        when(this.client.queryByK8sNamespace(any(SignalFxProgram.class), any(), any(), any()))
            .thenThrow(new SignalFxQueryException("SignalFlow API returned status 404"));

        MetricEvidence metrics = collector().collect(alert("reward-service", triggered));

        assertThat(metrics.errorRateDuring()).isEqualTo(0.40);
        assertThat(metrics.exports()).hasSize(5);
        assertThat(metrics.exports())
            .filteredOn(e -> e.program().equals("CPU_UTILIZATION"))
            .singleElement()
            .satisfies(cpu -> assertThat(cpu.error()).contains("404"));
    }

    private void stub(SignalFxProgram program, String json) {
        when(this.client.query(eq(program), any(), any(), any())).thenReturn(json);
    }

    private void stubByNamespace(SignalFxProgram program, String json) {
        when(this.client.queryByK8sNamespace(eq(program), any(), any(), any())).thenReturn(json);
    }

    private static String counts(Instant triggered, double before, double during) {
        return """
            [{"timestampMs":%d,"value":%s},{"timestampMs":%d,"value":%s}]"""
            .formatted(triggered.minusSeconds(120).toEpochMilli(), before,
                triggered.plusSeconds(120).toEpochMilli(), during);
    }

    private SignalFlowSignalFxCollector collector() {
        return collector("reward-service");
    }

    private SignalFlowSignalFxCollector collector(String defaultService) {
        return new SignalFlowSignalFxCollector(this.client, new AgentProperties(
            "fixture", "P2UX5VH", null, null, null,
            new AgentProperties.SignalFx("eu0", "token", null, null,
                "https://app.eu0.signalfx.com/#/dashboard/demo", defaultService),
            null, null, null));
    }

    private static IncidentAlert alert(String serviceName, Instant triggered) {
        return new IncidentAlert("01JDEMOEVENT", "PINCIDENT", serviceName + " error rate increased",
            "P2UX5VH", serviceName, triggered,
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
    }
}
