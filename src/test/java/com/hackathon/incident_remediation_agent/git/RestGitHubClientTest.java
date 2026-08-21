package com.hackathon.incident_remediation_agent.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.DeploymentEvidence;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

/**
 * The commit is assembled out of API calls rather than a checkout, so the order and shape of those
 * calls <em>is</em> the behaviour. Getting the base tree wrong, for instance, produces a commit that
 * silently deletes every file it does not mention.
 */
class RestGitHubClientTest {

    private static final String BASE = "https://api.github.com";

    private static final String REPO = BASE + "/repos/Flutter-Global/darsrftp-service";

    private static final String SOURCE =
        "src/main/java/com/flutter/reward_service/service/KafkaConsumer.java";

    private static final AgentProperties.RepositoryTarget TARGET =
        new AgentProperties.RepositoryTarget("Flutter-Global/darsrftp-service", "dev",
            List.of("src/main/java/"), List.of(SOURCE));

    private static final FixProposal PROPOSAL = new FixProposal(true,
        "RewardEvent.getPayload() can be null",
        "Guard the null reward payload",
        Map.of(SOURCE, "class KafkaConsumer { /* guarded */ }"));

    private MockRestServiceServer server;

    private RestGitHubClient client;

    @BeforeEach
    void setUp() {
        givenPushEnabled(true);
    }

    @Test
    void buildsTheCommitFromBlobsAndOpensADraftPullRequestAgainstTheBaseBranch() {
        expectBaseRefLookup();

        server.expect(requestTo(REPO + "/git/blobs")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.content").value("class KafkaConsumer { /* guarded */ }"))
            .andExpect(jsonPath("$.encoding").value("utf-8"))
            .andRespond(withSuccess("{\"sha\":\"blob-sha\"}", MediaType.APPLICATION_JSON));

        // base_tree must be the commit's tree, never the commit id.
        server.expect(requestTo(REPO + "/git/trees")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.base_tree").value("base-tree-sha"))
            .andExpect(jsonPath("$.tree[0].path").value(SOURCE))
            .andExpect(jsonPath("$.tree[0].sha").value("blob-sha"))
            .andExpect(jsonPath("$.tree[0].mode").value("100644"))
            .andRespond(withSuccess("{\"sha\":\"new-tree-sha\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(REPO + "/git/commits")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.message").value("fix(SCRUM-1): Guard the null reward payload"))
            .andExpect(jsonPath("$.tree").value("new-tree-sha"))
            .andExpect(jsonPath("$.parents[0]").value("base-commit-sha"))
            .andRespond(withSuccess("{\"sha\":\"new-commit-sha\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(REPO + "/git/refs")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.ref")
                .value("refs/heads/agent/incident/SCRUM-1-guard-the-null-reward-payload"))
            .andExpect(jsonPath("$.sha").value("new-commit-sha"))
            .andRespond(withSuccess("{\"ref\":\"refs/heads/x\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(REPO + "/pulls")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.title").value("[SCRUM-1] Guard the null reward payload"))
            .andExpect(jsonPath("$.head")
                .value("agent/incident/SCRUM-1-guard-the-null-reward-payload"))
            .andExpect(jsonPath("$.base").value("dev"))
            .andExpect(jsonPath("$.draft").value(true))
            .andRespond(withSuccess("""
                {"number":73,"html_url":"https://github.com/Flutter-Global/darsrftp-service/pull/73",
                 "draft":true}""", MediaType.APPLICATION_JSON));

        FixSubmission submission = client.submitFix(pack(), PROPOSAL, TARGET, List.of(SOURCE));

        server.verify();
        assertThat(submission.pushed()).isTrue();
        assertThat(submission.branch())
            .isEqualTo("agent/incident/SCRUM-1-guard-the-null-reward-payload");
        assertThat(submission.commitSha()).isEqualTo("new-commit-sha");
        assertThat(submission.pullRequest().number()).isEqualTo(73);
        assertThat(submission.pullRequest().url())
            .isEqualTo(URI.create("https://github.com/Flutter-Global/darsrftp-service/pull/73"));
    }

    /** An executable file must keep its bit; writing 100644 over it is a silent regression. */
    @Test
    void preservesTheExistingFileMode() {
        server.expect(requestTo(REPO + "/git/ref/heads/dev")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"object\":{\"sha\":\"base-commit-sha\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/commits/base-commit-sha"))
            .andRespond(withSuccess("{\"tree\":{\"sha\":\"base-tree-sha\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/trees/base-tree-sha?recursive=1"))
            .andRespond(withSuccess("""
                {"tree":[{"path":"%s","mode":"100755","type":"blob"}],"truncated":false}"""
                .formatted(SOURCE), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/blobs"))
            .andRespond(withSuccess("{\"sha\":\"blob-sha\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/trees")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.tree[0].mode").value("100755"))
            .andRespond(withSuccess("{\"sha\":\"new-tree-sha\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/commits")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"sha\":\"new-commit-sha\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/refs"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/pulls"))
            .andRespond(withSuccess("{\"number\":1,\"html_url\":\"https://x/1\",\"draft\":true}",
                MediaType.APPLICATION_JSON));

        client.submitFix(pack(), PROPOSAL, TARGET, List.of(SOURCE));

        server.verify();
    }

    /**
     * A non-draft pull request can be reviewed and merged, which is outside what this agent is
     * allowed to produce.
     */
    @Test
    void rejectsAPullRequestGitHubDidNotCreateAsADraft() {
        expectBaseRefLookup();
        server.expect(requestTo(REPO + "/git/blobs"))
            .andRespond(withSuccess("{\"sha\":\"blob-sha\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/trees"))
            .andRespond(withSuccess("{\"sha\":\"new-tree-sha\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/commits"))
            .andRespond(withSuccess("{\"sha\":\"new-commit-sha\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/refs"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/pulls"))
            .andRespond(withSuccess("{\"number\":73,\"html_url\":\"https://x/73\",\"draft\":false}",
                MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> client.submitFix(pack(), PROPOSAL, TARGET, List.of(SOURCE)))
            .withMessageContaining("non-draft");
    }

    /** With pushing disabled nothing may reach GitHub at all, not even a read. */
    @Test
    void makesNoRequestsWhenPushingIsDisabled() {
        givenPushEnabled(false);

        FixSubmission submission = client.submitFix(pack(), PROPOSAL, TARGET, List.of(SOURCE));

        server.verify();
        assertThat(submission.pushed()).isFalse();
        assertThat(submission.commitSha()).isNull();
        assertThat(submission.branch())
            .isEqualTo("agent/incident/SCRUM-1-guard-the-null-reward-payload");
        assertThat(submission.changedFiles()).containsExactly(SOURCE);
    }

    private void expectBaseRefLookup() {
        server.expect(requestTo(REPO + "/git/ref/heads/dev")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"object\":{\"sha\":\"base-commit-sha\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/commits/base-commit-sha"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"tree\":{\"sha\":\"base-tree-sha\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/trees/base-tree-sha?recursive=1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"tree\":[],\"truncated\":false}", MediaType.APPLICATION_JSON));
    }

    private void givenPushEnabled(boolean pushEnabled) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new RestGitHubClient(builder, new AgentProperties(
            "fixture", "P2UX5VH", null, null, null, null, null, null,
            new AgentProperties.GitHub(BASE, "token", "agent/incident", pushEnabled, 5,
                List.of(), Map.of(), Map.of())));
    }

    private static EvidencePack pack() {
        IncidentAlert alert = new IncidentAlert("01JDEMOEVENT", "PINCIDENT",
            "reward-service error rate increased", "P2UX5VH", "reward-service",
            Instant.parse("2026-08-14T02:14:00Z"),
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
        return new EvidencePack(alert,
            new JiraTicket("SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1")),
            new LogEvidence(184, "NullPointerException", List.of("sample"), null),
            new MetricEvidence(0.001, 0.4, false, null),
            new DeploymentEvidence("v1.4.2", "abc123", null,
                Instant.parse("2026-08-14T02:04:00Z")),
            "application_error", "evidence-sha");
    }
}
