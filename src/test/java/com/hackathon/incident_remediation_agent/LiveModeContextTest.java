package com.hackathon.incident_remediation_agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.hackathon.incident_remediation_agent.ai.AiFixClient;
import com.hackathon.incident_remediation_agent.ai.AiRepositorySelector;
import com.hackathon.incident_remediation_agent.ai.RestAiFixClient;
import com.hackathon.incident_remediation_agent.workflow.IncidentWorkflow;

/**
 * Live mode swaps the AI adapter, and every conditional bean has to still resolve. Without this the
 * first real run fails at startup rather than in a test.
 */
@SpringBootTest(properties = "agent.mode=live")
class LiveModeContextTest {

    @Autowired
    AiFixClient aiFixClient;

    @Autowired
    AiRepositorySelector repositorySelector;

    @Autowired
    IncidentWorkflow workflow;

    @Test
    void wiresTheLiveModelAdapterAndTheRestOfTheWorkflow() {
        assertThat(this.aiFixClient).isInstanceOf(RestAiFixClient.class);
        assertThat(this.repositorySelector).isNotNull();
        assertThat(this.workflow).isNotNull();
    }
}
