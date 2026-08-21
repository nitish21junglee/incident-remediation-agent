package com.hackathon.incident_remediation_agent.evidence.signalfx;

/**
 * Known {@code service.name} values usable as a SignalFlow filter, e.g. in a program such as
 * {@code data('http.server.requests_count', filter=filter('service.name', '...')).sum().publish()}.
 *
 * <p>Use {@link #toFilterClause()} to render the {@code filter(...)} clause directly into a
 * SignalFlow program template.
 */
public enum SignalFxService {

    LEAGUE_ADMIN("league-admin-service"),
    LEADERBOARD("leaderboard-service"),
    CONSUMER_GATEWAY("consumer-gateway-service"),
    CLICK("click-service"),
    REWARD("reward-service"),
    SCHEDULER("scheduler-service");

    private final String filterValue;

    SignalFxService(String filterValue) {
        this.filterValue = filterValue;
    }

    /** The raw {@code service.name} value as reported by SignalFx. */
    public String filterValue() {
        return filterValue;
    }

    /** Renders a ready-to-embed SignalFlow {@code filter(...)} clause for this service. */
    public String toFilterClause() {
        return "filter('service.name', '%s')".formatted(filterValue);
    }
}
