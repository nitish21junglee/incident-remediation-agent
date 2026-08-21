package com.hackathon.incident_remediation_agent.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

/**
 * Resolves which models a task may use, in preference order.
 *
 * <p>Three layers, highest first: a runtime override, the configured per-task chain, then the
 * default chain. Returning a list rather than one name lets the caller fall through to the next
 * candidate when a model is unavailable or rate-limited, which Gemini's free tier does routinely.
 *
 * <p>Overrides are held in memory only, so a restart returns to configuration.
 */
@Component
public class AiModelSelector {

    /** Model names end up in a JSON request body; this keeps them to an obviously safe shape. */
    private static final Pattern VALID_MODEL = Pattern.compile("[A-Za-z0-9._:/-]{1,100}");

    private static final Logger log = LoggerFactory.getLogger(AiModelSelector.class);

    private final List<String> defaultModels;
    private final Map<String, List<String>> configured;
    private final Map<String, List<String>> overrides = new ConcurrentHashMap<>();

    AiModelSelector(AgentProperties properties) {
        AgentProperties.Ai ai = properties.ai();
        this.defaultModels = clean(ai == null ? null : ai.defaultModels());
        this.configured = new LinkedHashMap<>();
        if (ai != null && ai.models() != null) {
            ai.models().forEach((key, chain) -> this.configured.put(normalise(key), clean(chain)));
        }
    }

    /**
     * @return the ordered candidate models for the task, never empty
     * @throws IllegalStateException when neither the task nor the default chain is configured
     */
    public List<String> candidatesFor(AiTask task) {
        List<String> override = this.overrides.get(task.key());
        if (override != null && !override.isEmpty()) {
            return override;
        }
        List<String> fromConfiguration = this.configured.get(task.key());
        if (fromConfiguration != null && !fromConfiguration.isEmpty()) {
            return fromConfiguration;
        }
        if (this.defaultModels.isEmpty()) {
            throw new IllegalStateException(
                "No model configured for task %s and agent.ai.default-models is empty"
                    .formatted(task.key()));
        }
        return this.defaultModels;
    }

    public void override(AiTask task, List<String> models) {
        List<String> chain = clean(models);
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one model");
        }
        this.overrides.put(task.key(), chain);
        // Logged at warn because it silently changes what every later incident runs on.
        log.warn("AI model override set for task {}: {}", task.key(), chain);
    }

    public void clearOverride(AiTask task) {
        if (this.overrides.remove(task.key()) != null) {
            log.warn("AI model override cleared for task {}", task.key());
        }
    }

    /** @return effective chain per task, for the admin endpoint */
    public Map<String, List<String>> snapshot() {
        Map<String, List<String>> effective = new LinkedHashMap<>();
        for (AiTask task : AiTask.values()) {
            effective.put(task.key(), candidatesFor(task));
        }
        return effective;
    }

    private static List<String> clean(List<String> models) {
        if (models == null) {
            return List.of();
        }
        List<String> chain = new ArrayList<>();
        for (String model : models) {
            if (model == null || model.isBlank()) {
                continue;
            }
            String trimmed = model.trim();
            if (!VALID_MODEL.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("'%s' is not a valid model name".formatted(trimmed));
            }
            if (!chain.contains(trimmed)) {
                chain.add(trimmed);
            }
        }
        return List.copyOf(chain);
    }

    private static String normalise(String key) {
        return key == null ? "" : key.trim().toLowerCase();
    }
}
