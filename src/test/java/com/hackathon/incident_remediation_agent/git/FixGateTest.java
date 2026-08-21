package com.hackathon.incident_remediation_agent.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

/**
 * With no checkout there is no {@code git apply} to prove the model read the real file, so these
 * checks are the only thing standing between a model's output and a branch.
 */
class FixGateTest {

    private static final String SOURCE = "src/main/java/com/flutter/reward_service/service/KafkaConsumer.java";

    private static final String TEST = "src/test/java/com/flutter/reward_service/service/KafkaConsumerTest.java";

    private static final String ORIGINAL = "class KafkaConsumer { void consume() { body(); } }";

    private static final AgentProperties.RepositoryTarget TARGET =
        new AgentProperties.RepositoryTarget(
            "Flutter-Global/darsrftp-service", "dev", List.of(SOURCE, TEST));

    private final FixGate gate = gate(5, List.of(".github/workflows/", "infrastructure/"));

    @Test
    void acceptsAChangeToAnAllowlistedFile() {
        List<String> changed = gate.check(
            Map.of(SOURCE, ORIGINAL + " // guarded"), Map.of(SOURCE, ORIGINAL), TARGET);

        assertThat(changed).containsExactly(SOURCE);
    }

    @Test
    void rejectsAPathOutsideTheConfiguredContextFiles() {
        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> gate.check(
                Map.of("src/main/java/Other.java", "class Other {}"), Map.of(), TARGET))
            .withMessageContaining("not a configured context file");
    }

    @Test
    void rejectsProtectedPaths() {
        AgentProperties.RepositoryTarget target = new AgentProperties.RepositoryTarget(
            "Flutter-Global/darsrftp-service", "dev", List.of(".github/workflows/ci.yaml"));

        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> gate.check(
                Map.of(".github/workflows/ci.yaml", "on: push"), Map.of(), target))
            .withMessageContaining("protected path");
    }

    @Test
    void rejectsAnEmptyProposal() {
        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> gate.check(Map.of(), Map.of(), TARGET))
            .withMessageContaining("no file changes");
    }

    @Test
    void rejectsTooManyChangedFiles() {
        FixGate strict = gate(1, List.of());
        Map<String, String> proposed = new LinkedHashMap<>();
        proposed.put(SOURCE, ORIGINAL + " // a");
        proposed.put(TEST, "class KafkaConsumerTest {} // b");

        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> strict.check(proposed, Map.of(), TARGET))
            .withMessageContaining("the limit is 1");
    }

    @Test
    void rejectsAnEmptiedFile() {
        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> gate.check(Map.of(SOURCE, "   "), Map.of(SOURCE, ORIGINAL), TARGET))
            .withMessageContaining("empties");
    }

    /** The failure this catches: a model asked to patch one line returns a truncated rewrite. */
    @Test
    void rejectsAFileThatLostMoreThanHalfItsContent() {
        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> gate.check(
                Map.of(SOURCE, "class KafkaConsumer {}"), Map.of(SOURCE, ORIGINAL), TARGET))
            .withMessageContaining("truncated");
    }

    @Test
    void rejectsAFileThatDidNotChange() {
        assertThatExceptionOfType(PatchValidationException.class)
            .isThrownBy(() -> gate.check(Map.of(SOURCE, ORIGINAL), Map.of(SOURCE, ORIGINAL), TARGET))
            .withMessageContaining("unchanged");
    }

    private static FixGate gate(int maxChangedFiles, List<String> protectedPaths) {
        return new FixGate(new AgentProperties(
            "fixture", "P2UX5VH", null, null, null, null, null, null,
            new AgentProperties.GitHub("https://api.github.com", "token", "agent/incident",
                false, maxChangedFiles, protectedPaths, Map.of(), Map.of())));
    }
}
