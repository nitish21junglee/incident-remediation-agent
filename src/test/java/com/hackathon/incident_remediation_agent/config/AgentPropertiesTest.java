package com.hackathon.incident_remediation_agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "agent.mode=fixture",
    "agent.service-id=PDEMO",
    "agent.jira.project-key=HACK",
    "agent.github.push-enabled=true",
    "agent.github.max-changed-files=3",
    "agent.github.repositories.demo-api.slug=acme/demo-api",
    "agent.github.repositories.demo-api.base-branch=dev",
    "agent.github.repositories.demo-api.context-files[0]=src/main/java/Mapper.java",
    "agent.github.service-repositories.PDEMO=demo-api"
})
class AgentPropertiesTest {

    @Autowired
    AgentProperties properties;

    @Test
    void bindsAgentSettings() {
        assertThat(properties.mode()).isEqualTo("fixture");
        assertThat(properties.serviceId()).isEqualTo("PDEMO");
        assertThat(properties.jira().projectKey()).isEqualTo("HACK");
        assertThat(properties.github().serviceRepositories()).containsEntry("PDEMO", "demo-api");
        AgentProperties.RepositoryTarget target = properties.github().repositories().get("demo-api");
        assertThat(target.slug()).isEqualTo("acme/demo-api");
        assertThat(target.baseBranch()).isEqualTo("dev");
        assertThat(target.contextFiles()).containsExactly("src/main/java/Mapper.java");
        assertThat(properties.github().pushEnabled()).isTrue();
        assertThat(properties.github().maxChangedFiles()).isEqualTo(3);
    }
}
