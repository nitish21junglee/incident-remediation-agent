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
    "agent.github.repositories.demo-api.source-roots[0]=src/main/java/",
    "agent.github.repositories.demo-api.writable-paths[0]=src/main/java/com/acme/",
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
        assertThat(target.sourceRoots()).containsExactly("src/main/java/");
        assertThat(target.writablePaths()).containsExactly("src/main/java/com/acme/");
        assertThat(properties.github().pushEnabled()).isTrue();
        assertThat(properties.github().maxChangedFiles()).isEqualTo(3);
    }
}
