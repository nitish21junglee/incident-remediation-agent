package com.hackathon.incident_remediation_agent.evidence.signalfx;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns SignalFlow data points into {@link MetricEvidence} by comparing the window before the
 * incident fired against the window after it.
 *
 * <p>The API only accepts a {@code [start, stop]} window, so one query spans both sides and the
 * points are split locally at {@link IncidentAlert#triggeredAt()}. That keeps this to two queries
 * for error rate and one for latency, and avoids touching
 * {@link SignalFxSignalFlowClient}, which already works.
 *
 * <p>A SignalFx failure yields zeroed evidence rather than an exception. Zeroed metrics make
 * {@code EvidenceClassifier} return {@code unknown}, which stops the AI path — so losing metrics
 * fails towards proposing nothing, never towards proposing a blind fix.
 */
@Component
public class SignalFlowSignalFxCollector implements SignalFxCollector {

    private static final Logger log = LoggerFactory.getLogger(SignalFlowSignalFxCollector.class);

    private static final Duration MIN_LOOKBACK = Duration.ofMinutes(30);
    private static final Duration MAX_LOOKBACK = Duration.ofHours(6);
    private static final Duration RESOLUTION = Duration.ofMinutes(1);

    /** Latency has to move by more than this factor before it counts as a change. */
    private static final double LATENCY_CHANGE_FACTOR = 1.5;

    private static final MetricEvidence NO_METRICS = new MetricEvidence(0, 0, false, null);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SignalFxSignalFlowClient client;
    private final String defaultService;
    private final URI dashboardUrl;

    SignalFlowSignalFxCollector(SignalFxSignalFlowClient client, AgentProperties properties) {
        AgentProperties.SignalFx signalfx = properties.signalfx();
        this.client = client;
        this.defaultService = signalfx.defaultService();
        this.dashboardUrl = signalfx.dashboardLink() == null || signalfx.dashboardLink().isBlank()
            ? null
            : URI.create(signalfx.dashboardLink());
    }

    @Override
    public MetricEvidence collect(IncidentAlert alert) {
        Optional<SignalFxService> service = resolveService(alert);
        if (service.isEmpty()) {
            log.warn("No SignalFx service matches '{}' and agent.signalfx.default-service is unset; "
                + "collecting no metrics", alert.serviceName());
            return NO_METRICS;
        }

        Duration lookback = lookbackFor(alert);
        Instant splitAt = splitPoint(alert, lookback);

        try {
            Window errors = window(SignalFxProgram.ERROR_COUNT, service.get(), lookback, splitAt);
            Window requests = window(SignalFxProgram.REQUEST_COUNT, service.get(), lookback, splitAt);
            Window latency = window(SignalFxProgram.LATENCY_BY_URI, service.get(), lookback, splitAt);

            return new MetricEvidence(
                rate(errors.beforeTotal(), requests.beforeTotal()),
                rate(errors.duringTotal(), requests.duringTotal()),
                latencyChanged(latency),
                this.dashboardUrl);
        }
        catch (SignalFxQueryException exception) {
            log.warn("SignalFx query failed for {}; continuing without metrics",
                alert.incidentId(), exception);
            return NO_METRICS;
        }
    }

    /**
     * Matches the incident's service name against the known SignalFx services, tolerating a missing
     * {@code -service} suffix, then falls back to the configured default.
     */
    private Optional<SignalFxService> resolveService(IncidentAlert alert) {
        return byName(alert.serviceName()).or(() -> byName(this.defaultService));
    }

    private static Optional<SignalFxService> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        if ("darsrftp-service".equals(wanted)) {
            wanted = SignalFxService.REWARD.filterValue().toLowerCase(Locale.ROOT);
        }
        String suffixed = wanted.endsWith("-service") ? wanted : wanted + "-service";
        for (SignalFxService candidate : SignalFxService.values()) {
            String value = candidate.filterValue().toLowerCase(Locale.ROOT);
            if (value.equals(wanted) || value.equals(suffixed)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Twice the incident's age, so the before and after windows are comparable in length. */
    private static Duration lookbackFor(IncidentAlert alert) {
        Duration age = Duration.between(alert.triggeredAt(), Instant.now());
        Duration doubled = age.isNegative() ? MIN_LOOKBACK : age.multipliedBy(2);
        if (doubled.compareTo(MIN_LOOKBACK) < 0) {
            return MIN_LOOKBACK;
        }
        return doubled.compareTo(MAX_LOOKBACK) > 0 ? MAX_LOOKBACK : doubled;
    }

    /**
     * Where "before" ends and "during" begins. An incident older than the deepest window we are
     * willing to query cannot be split at its own trigger time, so the window is halved instead and
     * the compromise is logged.
     */
    private static Instant splitPoint(IncidentAlert alert, Duration lookback) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(lookback);
        Instant triggeredAt = alert.triggeredAt();
        if (triggeredAt.isAfter(windowStart) && triggeredAt.isBefore(now)) {
            return triggeredAt;
        }
        log.warn("Incident {} triggered at {}, outside the {} query window; splitting the window in "
            + "half instead", alert.incidentId(), triggeredAt, lookback);
        return windowStart.plus(lookback.dividedBy(2));
    }

    private Window window(
        SignalFxProgram program,
        SignalFxService service,
        Duration lookback,
        Instant splitAt
    ) {
        String json = this.client.query(program, service, lookback, RESOLUTION);
        log.info("SignalFx {} response for {}: {}", program, service.filterValue(), json);
        return split(parse(json), splitAt);
    }

    private List<Point> parse(String json) {
        JsonNode array;
        try {
            array = this.objectMapper.readTree(json);
        }
        catch (RuntimeException exception) {
            throw new SignalFxQueryException("Cannot parse SignalFlow data points", exception);
        }

        List<Point> points = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode timestamp = node.get("timestampMs");
            JsonNode value = node.get("value");
            if (timestamp == null || value == null) {
                continue;
            }
            JsonNode label = node.get("label");
            points.add(new Point(
                Instant.ofEpochMilli(timestamp.asLong()),
                value.asDouble(),
                label == null ? null : label.asString()));
        }
        return points;
    }

    /**
     * {@code LATENCY_BY_URI} publishes an intermediate A and B that are disabled, and a computed C.
     * Keeping only C when it is present stops the raw counts being averaged in as if they were
     * latencies.
     */
    private static Window split(List<Point> points, Instant splitAt) {
        boolean hasComputed = points.stream().anyMatch(point -> "C".equals(point.label()));
        List<Point> relevant = hasComputed
            ? points.stream().filter(point -> "C".equals(point.label())).toList()
            : points;

        List<Double> before = new ArrayList<>();
        List<Double> during = new ArrayList<>();
        for (Point point : relevant) {
            if (point.at().isBefore(splitAt)) {
                before.add(point.value());
            }
            else {
                during.add(point.value());
            }
        }
        return new Window(List.copyOf(before), List.copyOf(during));
    }

    private static double rate(double errors, double requests) {
        return requests <= 0 ? 0 : errors / requests;
    }

    private static boolean latencyChanged(Window latency) {
        double before = latency.beforeMean();
        double during = latency.duringMean();
        return before > 0 && during > before * LATENCY_CHANGE_FACTOR;
    }

    private record Point(Instant at, double value, String label) {}

    private record Window(List<Double> before, List<Double> during) {

        double beforeTotal() {
            return this.before.stream().mapToDouble(Double::doubleValue).sum();
        }

        double duringTotal() {
            return this.during.stream().mapToDouble(Double::doubleValue).sum();
        }

        double beforeMean() {
            return this.before.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        double duringMean() {
            return this.during.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
    }
}
