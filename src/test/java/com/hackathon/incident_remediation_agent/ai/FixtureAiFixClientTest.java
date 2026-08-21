package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class FixtureAiFixClientTest {

    private static final String PATH = "src/main/java/Mapper.java";

    private final FixtureAiFixClient client = new FixtureAiFixClient();

    @Test
    void loadsTheDeterministicProposal() {
        FixProposal proposal = client.propose(null, Map.of(PATH, "class Mapper {}"));

        assertThat(proposal.probableFix()).isTrue();
        assertThat(proposal.hypothesis()).startsWith("Fixture proposal:");
        assertThat(proposal.summary()).isEqualTo("Fixture change from the incident agent");
    }

    /**
     * The changed file has to be one it was given, otherwise the gates would reject every fixture
     * run for writing outside the allowlist.
     */
    @Test
    void rewritesAFileItWasGiven() {
        FixProposal proposal = client.propose(null, Map.of(PATH, "class Mapper {}"));

        assertThat(proposal.files()).containsOnlyKeys(PATH);
        assertThat(proposal.files().get(PATH))
            .startsWith("class Mapper {}")
            .contains("Not a real fix");
    }

    /** No files means nothing can be proposed, whatever the fixture says. */
    @Test
    void declinesWhenNoFilesWereRead() {
        FixProposal proposal = client.propose(null, Map.of());

        assertThat(proposal.probableFix()).isFalse();
        assertThat(proposal.files()).isEmpty();
    }

    /** Determinism is the whole point of fixture mode; repeated calls must not drift. */
    @Test
    void returnsTheSameProposalEveryCall() {
        Map<String, String> files = Map.of(PATH, "class Mapper {}");

        assertThat(client.propose(null, files)).isEqualTo(client.propose(null, files));
    }
}
