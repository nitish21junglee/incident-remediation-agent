package com.hackathon.incident_remediation_agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "agent.mode=fixture",
    "agent.service-id=PDEMO",
    "agent.jira.project-key=HACK",
    "agent.github.repository=acme/demo-api",
    "agent.github.validation-command[0]=./mvnw",
    "agent.github.validation-command[1]=-q",
    "agent.github.validation-command[2]=test"
})
class AgentPropertiesTest {

    @Autowired
    AgentProperties properties;

    @Test
    void bindsAgentSettings() {
        assertThat(properties.mode()).isEqualTo("fixture");
        assertThat(properties.serviceId()).isEqualTo("PDEMO");
        assertThat(properties.jira().projectKey()).isEqualTo("HACK");
        assertThat(properties.github().validationCommand())
            .containsExactly("./mvnw", "-q", "test");
    }
}
