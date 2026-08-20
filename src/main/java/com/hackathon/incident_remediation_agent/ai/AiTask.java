package com.hackathon.incident_remediation_agent.ai;

import java.util.Arrays;

/**
 * The distinct jobs the service asks a model to do. Each maps to a configuration key under
 * {@code agent.ai.models.*} so tasks can run on different models.
 */
public enum AiTask {

    FIX_PROPOSAL("fix-proposal");

    private final String key;

    AiTask(String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public static AiTask fromKey(String key) {
        String normalised = key == null ? "" : key.trim().toLowerCase();
        return Arrays.stream(values())
            .filter(task -> task.key.equals(normalised))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown AI task: " + key));
    }
}
