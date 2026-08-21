package com.hackathon.incident_remediation_agent.evidence.signalfx;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.evidence.SignalFxExport;
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

    /**
     * Scoped by {@code service.name}. These three produce the error rate and latency signal
     * {@code EvidenceClassifier} decides on, so losing any of them means no metric evidence.
     */
    private static final List<SignalFxProgram> SERVICE_PROGRAMS = List.of(
        SignalFxProgram.ERROR_COUNT, SignalFxProgram.REQUEST_COUNT, SignalFxProgram.LATENCY_BY_URI);

    /**
     * Scoped by {@code k8s.namespace.name}, because container metrics are published per pod and do
     * not carry {@code service.name}. Additive context: nothing downstream classifies on them.
     */
    private static final List<SignalFxProgram> NAMESPACE_PROGRAMS = List.of(
        SignalFxProgram.CPU_UTILIZATION, SignalFxProgram.MEMORY_UTILIZATION);

    /**
     * Guard on what is handed to Mongo. Past this the raw export is dropped rather than truncated:
     * half a JSON array cannot be read back, and the aggregates carry the signal regardless.
     */
    private static final int MAX_STORED_RAW_CHARACTERS = 500_000;

    private static final MetricEvidence NO_METRICS =
        new MetricEvidence(0, 0, false, null, List.of());

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
        Optional<SignalFxService> resolved = resolveService(alert);
        if (resolved.isEmpty()) {
            log.warn("No SignalFx service matches '{}' and agent.signalfx.default-service is unset; "
                + "collecting no metrics", alert.serviceName());
            return NO_METRICS;
        }
        SignalFxService service = resolved.get();
        Duration lookback = lookbackFor(alert);
        Instant splitAt = splitPoint(alert, lookback);

        List<SignalFxExport> exports = new ArrayList<>();
        Map<SignalFxProgram, Window> windows = new EnumMap<>(SignalFxProgram.class);

        try {
            for (SignalFxProgram program : SERVICE_PROGRAMS) {
                windows.put(program, run(program, service, lookback, splitAt, false, exports));
            }
        }
        catch (SignalFxQueryException exception) {
            log.warn("SignalFx query failed for {}; continuing without metrics",
                alert.incidentId(), exception);
            return NO_METRICS;
        }

        // Tolerated one at a time: the container metrics are extra context, and the k8s namespace
        // they need is a second guess at what the incident's service is called. Losing them must
        // not cost the error-rate evidence that has already been collected.
        for (SignalFxProgram program : NAMESPACE_PROGRAMS) {
            try {
                run(program, service, lookback, splitAt, true, exports);
            }
            catch (SignalFxQueryException exception) {
                log.warn("SignalFx {} failed for {}; continuing without it",
                    program, alert.incidentId(), exception);
                exports.add(SignalFxExport.failed(program.name(),
                    service.toK8sNamespaceFilterClause(), exception.getMessage()));
            }
        }

        Window errors = windows.get(SignalFxProgram.ERROR_COUNT);
        Window requests = windows.get(SignalFxProgram.REQUEST_COUNT);
        return new MetricEvidence(
            rate(errors.beforeTotal(), requests.beforeTotal()),
            rate(errors.duringTotal(), requests.duringTotal()),
            latencyChanged(windows.get(SignalFxProgram.LATENCY_BY_URI)),
            this.dashboardUrl,
            List.copyOf(exports));
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

    /** Runs one program, records its export, and returns its split window. */
    private Window run(
        SignalFxProgram program,
        SignalFxService service,
        Duration lookback,
        Instant splitAt,
        boolean byNamespace,
        List<SignalFxExport> exports
    ) {
        String filter = byNamespace
            ? service.toK8sNamespaceFilterClause()
            : service.toFilterClause();
        String json = byNamespace
            ? this.client.queryByK8sNamespace(program, service, lookback, RESOLUTION)
            : this.client.query(program, service, lookback, RESOLUTION);
        log.info("SignalFx {} response for {}: {}", program, filter, json);

        List<Point> points = parse(json);
        Window window = split(points, splitAt);
        exports.add(new SignalFxExport(
            program.name(),
            filter,
            points.size(),
            SignalFxExport.Aggregate.of(window.before()),
            SignalFxExport.Aggregate.of(window.during()),
            storable(program, json),
            null));
        return window;
    }

    private static String storable(SignalFxProgram program, String json) {
        if (json != null && json.length() > MAX_STORED_RAW_CHARACTERS) {
            log.warn("SignalFx {} returned {} characters, over the {} character store budget; "
                + "keeping the aggregates only", program, json.length(), MAX_STORED_RAW_CHARACTERS);
            return null;
        }
        return json;
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
