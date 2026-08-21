package com.hackathon.incident_remediation_agent.evidence.signalfx;

/**
 * Predefined SignalFlow programs for common incident-evidence queries. Each template has a
 * single {@code %s} placeholder (usable more than once) for a filter clause; resolve it with
 * {@link #forService(SignalFxService)} for a {@code service.name} filter, or
 * {@link #forK8sNamespace(SignalFxService)} for a {@code k8s.namespace.name} filter.
 *
 * <p>Metric names below follow the Micrometer/OpenTelemetry conventions observed in this
 * environment (e.g. {@code http.server.requests_count}) - adjust them if your instrumentation
 * reports under different names.
 *
 * <p>{@link #CPU_UTILIZATION} and {@link #MEMORY_UTILIZATION} are filtered by k8s namespace
 * rather than service name, and aggregate {@code by} the pod/node/cluster dimensions rather than
 * collapsing them - the underlying container metrics are published once per pod, so every point
 * SignalFx returns still carries its {@code k8s.pod.name} (etc.) dimension. Nothing here reduces
 * that to a single number per service yet; a caller that needs one (rather than a per-pod
 * breakdown) has to aggregate across pods itself.
 */
public enum SignalFxProgram {

    CPU_UTILIZATION("""
        A = data('container_cpu_utilization', filter=%1$s, rollup='rate').sum(by=['k8s.pod.name', 'k8s.node.name', 'k8s.cluster.name', 'k8s.pod.uid']).scale(0.01).publish(label='A', enable=False)
        B = data('container.cpu.time', filter=%1$s).sum(by=['k8s.pod.name', 'k8s.node.name', 'k8s.cluster.name', 'k8s.pod.uid']).publish(label='B', enable=False)
        C = data('k8s.container.cpu_limit', filter=%1$s).sum(by=['k8s.pod.name', 'k8s.node.name', 'k8s.cluster.name', 'k8s.pod.uid']).publish(label='C', enable=False)
        D = ((A*100)/C).publish(label='D')
        """),

    MEMORY_UTILIZATION("""
        A = data('container.memory.usage', filter=%1$s).sum(by=['k8s.container.name', 'k8s.pod.name', 'k8s.node.name', 'k8s.cluster.name', 'k8s.pod.uid']).publish(label='A', enable=False)
        B = data('k8s.container.memory_limit', filter=%1$s).sum(by=['k8s.container.name', 'k8s.pod.name', 'k8s.node.name', 'k8s.cluster.name', 'k8s.pod.uid']).publish(label='B', enable=False)
        C = ((A*100)/B).publish(label='C')
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

    /** Renders this program with {@code service}'s {@code service.name} filter substituted in. */
    public String forService(SignalFxService service) {
        return template.formatted(service.toFilterClause());
    }

    /** Renders this program with {@code service}'s {@code k8s.namespace.name} filter substituted in. */
    public String forK8sNamespace(SignalFxService service) {
        return template.formatted(service.toK8sNamespaceFilterClause());
    }
}
