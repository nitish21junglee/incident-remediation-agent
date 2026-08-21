package com.hackathon.incident_remediation_agent.evidence.signalfx;

/**
 * Known {@code service.name} values usable as a SignalFlow filter, e.g. in a program such as
 * {@code data('http.server.requests_count', filter=filter('service.name', '...')).sum().publish()}.
 *
 * <p>Use {@link #toFilterClause()} to render the {@code filter(...)} clause directly into a
 * SignalFlow program template.
 *
 * <p>Each service also carries its {@code k8s.namespace.name} alias, since the two rarely match
 * (a dev namespace commonly suffixes {@code -dev}, and may not follow the {@code -service} naming
 * at all). Edit {@link #k8sNamespace} here when a namespace is renamed or a new environment is
 * added; use {@link #toK8sNamespaceFilterClause()} to render it into a program template.
 */
public enum SignalFxService {

    LEAGUE_ADMIN("league-admin-service", "dalerftp-service-dev"),
    LEADERBOARD("leaderboard-service", "dalsrftp-service-dev"),
    CONSUMER_GATEWAY("consumer-gateway-service", "dacgrftp-service-dev"),
    REWARD("reward-service", "darsrftp-service-dev"),
    SCHEDULER("scheduler-service", "dassrftp-service-dev");

    private final String filterValue;
    private final String k8sNamespace;

    SignalFxService(String filterValue, String k8sNamespace) {
        this.filterValue = filterValue;
        this.k8sNamespace = k8sNamespace;
    }

    /** The raw {@code service.name} value as reported by SignalFx. */
    public String filterValue() {
        return filterValue;
    }

    /** The raw {@code k8s.namespace.name} value this service's pods run under. */
    public String k8sNamespace() {
        return k8sNamespace;
    }

    /** Renders a ready-to-embed SignalFlow {@code filter(...)} clause for this service. */
    public String toFilterClause() {
        return "filter('service.name', '%s')".formatted(filterValue);
    }

    /** Renders a ready-to-embed SignalFlow {@code filter(...)} clause for this service's namespace. */
    public String toK8sNamespaceFilterClause() {
        return "filter('k8s.namespace.name', '%s')".formatted(k8sNamespace);
    }
}
