package com.hackathon.incident_remediation_agent.evidence.signalfx;

/**
 * Predefined SignalFlow programs for common incident-evidence queries. Each template has a
 * single {@code %s} placeholder (usable more than once) for a {@link SignalFxService} filter
 * clause; resolve it with {@link #forService(SignalFxService)}.
 *
 * <p>Metric names below follow the Micrometer/OpenTelemetry conventions observed in this
 * environment (e.g. {@code http.server.requests_count}) - adjust them if your instrumentation
 * reports under different names.
 */
public enum SignalFxProgram {

    CPU_UTILIZATION("""
        data('system.cpu.usage', filter=%1$s).mean().publish()
        """),

    MEMORY_UTILIZATION("""
        data('jvm.memory.used', filter=%1$s).sum().publish()
        """),

    REQUEST_COUNT("""
        data('http.server.requests_count', filter=%1$s).sum().publish()
        """),

    ERROR_COUNT("""
        data('http.server.requests_count', filter=%1$s and filter('status', '5*')).sum().publish()
        """),

    LATENCY_BY_URI("""
        A = data('http.server.requests_count', filter=%1$s).publish(label='A', enable=False)
        B = data('http.server.requests_sum', filter=%1$s).publish(label='B', enable=False)
        C = (B / A).sum(by=['uri']).publish(label='C')
        """);

    private final String template;

    SignalFxProgram(String template) {
        this.template = template;
    }

    /** Renders this program with {@code service}'s filter substituted into the template. */
    public String forService(SignalFxService service) {
        return template.formatted(service.toFilterClause());
    }
}
