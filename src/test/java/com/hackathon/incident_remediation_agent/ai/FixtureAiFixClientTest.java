package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class FixtureAiFixClientTest {

    private final FixtureAiFixClient client = new FixtureAiFixClient();

    @Test
    void loadsTheDeterministicProposal() {
        FixProposal proposal = client.propose(null, Map.of());

        assertThat(proposal.probableFix()).isTrue();
        assertThat(proposal.hypothesis())
            .isEqualTo("PaymentMapper dereferences a missing optional payment type.");
        assertThat(proposal.summary()).isEqualTo("Handle missing payment type");
        assertThat(proposal.unifiedDiff())
            .startsWith("diff --git a/src/main/java/com/example/demo/PaymentMapper.java")
            .contains("--- a/src/main/java/com/example/demo/PaymentMapper.java")
            .contains("+++ b/src/main/java/com/example/demo/PaymentMapper.java")
            .contains("-        return payment.getType().name();")
            .contains("+        return payment.getType() == null ? \"UNKNOWN\" : payment.getType().name();")
            .endsWith("\n");
    }

    /** Determinism is the whole point of fixture mode; repeated calls must not drift. */
    @Test
    void returnsTheSameProposalEveryCall() {
        assertThat(client.propose(null, Map.of())).isEqualTo(client.propose(null, Map.of()));
    }
}
