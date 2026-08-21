package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

class GitHubContextReaderTest {

    private static final String BASE = "https://api.github.com";

    private static final String SOURCE =
        "src/main/java/com/flutter/reward_service/service/KafkaConsumer.java";

    private static final AgentProperties.RepositoryTarget TARGET =
        new AgentProperties.RepositoryTarget(
            "Flutter-Global/darsrftp-service", "dev", List.of(SOURCE));

    private MockRestServiceServer server;

    private GitHubContextReader reader;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.reader = new GitHubContextReader(builder, new AgentProperties(
            "fixture", "P2UX5VH", null, null, null, null, null,
            new AgentProperties.GitHub(BASE, "token", "agent/incident", false, 5,
                List.of(), Map.of(), Map.of())));
    }

    /**
     * The raw media type matters: the default JSON representation is base64 with embedded newlines,
     * which the strict decoder rejects.
     */
    @Test
    void readsFileContentAtTheBaseBranchUsingTheRawMediaType() {
        this.server.expect(requestTo(
                BASE + "/repos/Flutter-Global/darsrftp-service/contents/" + SOURCE + "?ref=dev"))
            .andExpect(header("Accept", "application/vnd.github.raw"))
            .andExpect(header("Authorization", "Bearer token"))
            .andRespond(withSuccess("class KafkaConsumer {}", MediaType.TEXT_PLAIN));

        Map<String, String> files = this.reader.read(TARGET, List.of(SOURCE));

        this.server.verify();
        assertThat(files).containsExactly(org.assertj.core.api.Assertions.entry(
            SOURCE, "class KafkaConsumer {}"));
    }

    /** The slug contains a slash; sent as a template variable it becomes %2F and GitHub 404s. */
    @Test
    void doesNotEncodeTheSlashInTheRepositorySlug() {
        this.server.expect(requestTo(
                BASE + "/repos/Flutter-Global/darsrftp-service/contents/" + SOURCE + "?ref=dev"))
            .andRespond(withSuccess("class KafkaConsumer {}", MediaType.TEXT_PLAIN));

        this.reader.read(TARGET, List.of(SOURCE));

        this.server.verify();
    }

    @Test
    void refusesAbsolutePathsAndTraversal() {
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> this.reader.read(TARGET, List.of("/etc/passwd")))
            .withMessageContaining("repository-relative");
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> this.reader.read(TARGET, List.of("src/../../secrets.txt")))
            .withMessageContaining("traverse");
    }

    /** A brace would otherwise be read as a URI template expression. */
    @Test
    void refusesPathsWithUnsupportedCharacters() {
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> this.reader.read(TARGET, List.of("src/{evil}.java")))
            .withMessageContaining("unsupported characters");
    }

    @Test
    void refusesContentOverTheBudget() {
        this.server.expect(requestTo(
                BASE + "/repos/Flutter-Global/darsrftp-service/contents/" + SOURCE + "?ref=dev"))
            .andRespond(withSuccess("x".repeat(100_001), MediaType.TEXT_PLAIN));

        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> this.reader.read(TARGET, List.of(SOURCE)))
            .withMessageContaining("exceeds");
    }

    @Test
    void refusesAMalformedRepositorySlug() {
        AgentProperties.RepositoryTarget malformed =
            new AgentProperties.RepositoryTarget("not-a-slug", "dev", List.of(SOURCE));

        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> this.reader.read(malformed, List.of(SOURCE)))
            .withMessageContaining("repository slug");
    }
}
