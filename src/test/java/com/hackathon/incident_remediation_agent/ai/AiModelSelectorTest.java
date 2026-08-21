package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

class AiModelSelectorTest {

    @Test
    void usesThePerTaskChainWhenConfigured() {
        AiModelSelector selector = selector(
            List.of("flash"), Map.of("fix-proposal", List.of("pro", "flash")));

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL)).containsExactly("pro", "flash");
    }

    @Test
    void fallsBackToTheDefaultChain() {
        AiModelSelector selector = selector(List.of("flash", "flash-lite"), Map.of());

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL))
            .containsExactly("flash", "flash-lite");
    }

    @Test
    void treatsAnEmptyConfiguredChainAsAbsent() {
        AiModelSelector selector = selector(List.of("flash"), Map.of("fix-proposal", List.of()));

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL)).containsExactly("flash");
    }

    @Test
    void trimsBlanksAndDuplicatesPreservingOrder() {
        AiModelSelector selector = selector(
            List.of(), Map.of("fix-proposal", List.of(" pro ", "", "flash", "pro")));

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL)).containsExactly("pro", "flash");
    }

    @Test
    void failsLoudlyWhenNothingIsConfigured() {
        AiModelSelector selector = selector(List.of(), Map.of());

        assertThatIllegalStateException()
            .isThrownBy(() -> selector.candidatesFor(AiTask.FIX_PROPOSAL))
            .withMessageContaining("fix-proposal");
    }

    @Test
    void runtimeOverrideWinsOverConfiguration() {
        AiModelSelector selector = selector(
            List.of("flash"), Map.of("fix-proposal", List.of("pro")));

        selector.override(AiTask.FIX_PROPOSAL, List.of("experimental", "pro"));

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL))
            .containsExactly("experimental", "pro");
    }

    @Test
    void clearingAnOverrideRestoresConfiguration() {
        AiModelSelector selector = selector(
            List.of("flash"), Map.of("fix-proposal", List.of("pro")));
        selector.override(AiTask.FIX_PROPOSAL, List.of("experimental"));

        selector.clearOverride(AiTask.FIX_PROPOSAL);

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL)).containsExactly("pro");
    }

    @Test
    void rejectsAnEmptyOverride() {
        AiModelSelector selector = selector(List.of("flash"), Map.of());

        assertThatIllegalArgumentException()
            .isThrownBy(() -> selector.override(AiTask.FIX_PROPOSAL, List.of("  ")))
            .withMessageContaining("at least one model");
    }

    /** Model names reach a JSON request body, so keep them to an obviously safe shape. */
    @Test
    void rejectsImplausibleModelNames() {
        AiModelSelector selector = selector(List.of("flash"), Map.of());

        assertThatIllegalArgumentException()
            .isThrownBy(() -> selector.override(AiTask.FIX_PROPOSAL, List.of("pro; rm -rf /")))
            .withMessageContaining("not a valid model name");
    }

    @Test
    void snapshotReportsEffectiveChainsForEveryTask() {
        AiModelSelector selector = selector(
            List.of("flash"), Map.of("fix-proposal", List.of("pro")));

        assertThat(selector.snapshot()).containsEntry("fix-proposal", List.of("pro"));

        selector.override(AiTask.FIX_PROPOSAL, List.of("experimental"));
        assertThat(selector.snapshot()).containsEntry("fix-proposal", List.of("experimental"));
    }

    @Test
    void configuredKeysAreMatchedCaseInsensitively() {
        AiModelSelector selector = selector(List.of(), Map.of("FIX-PROPOSAL", List.of("pro")));

        assertThat(selector.candidatesFor(AiTask.FIX_PROPOSAL)).containsExactly("pro");
    }

    private static AiModelSelector selector(List<String> defaults, Map<String, List<String>> models) {
        return new AiModelSelector(new AgentProperties(
            "fixture", "PDEMO", null, null, null, null, null,
            new AgentProperties.Ai("https://ai.example", "key", defaults, models),
            null));
    }
}
