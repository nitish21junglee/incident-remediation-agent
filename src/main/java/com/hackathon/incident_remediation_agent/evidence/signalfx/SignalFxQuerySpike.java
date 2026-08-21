package com.hackathon.incident_remediation_agent.evidence.signalfx;

import java.time.Duration;

/**
 * Spike: exercises {@link SignalFxSignalFlowClient} against the real API and prints the
 * resolved JSON data points. Not wired into Spring - run main() directly to sanity-check
 * connectivity and query shape before this becomes {@link SignalFxCollector}.
 *
 * <p>Required env var: SIGNALFX_TOKEN.
 * Optional: SIGNALFX_REALM (default eu0).
 */
public final class SignalFxQuerySpike {

    public static void main(String[] args) {
        String token = require("SIGNALFX_TOKEN");
        String realm = System.getenv().getOrDefault("SIGNALFX_REALM", "eu0");

        SignalFxSignalFlowClient client = new SignalFxSignalFlowClient(token, realm);
        String json = client.query(
            SignalFxProgram.LATENCY_BY_URI,
            SignalFxService.LEAGUE_ADMIN,
            Duration.ofHours(1),
            Duration.ofMinutes(1));

        System.out.println(json);
    }

    private static String require(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + envVar);
        }
        return value;
    }

    private SignalFxQuerySpike() {
    }
}
