package com.hackathon.incident_remediation_agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import com.hackathon.incident_remediation_agent.ai.AiRouter;
import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.ai.RoutedFix;
import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.DeploymentEvidence;
import com.hackathon.incident_remediation_agent.evidence.EvidenceCollectionService;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.git.DraftPullRequest;
import com.hackathon.incident_remediation_agent.git.FixGate;
import com.hackathon.incident_remediation_agent.git.FixSubmission;
import com.hackathon.incident_remediation_agent.git.GitHubClient;
import com.hackathon.incident_remediation_agent.git.PatchValidationException;
import com.hackathon.incident_remediation_agent.git.RepositoryResolver;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.incident.WorkflowStage;
import com.hackathon.incident_remediation_agent.jira.JiraClient;
import com.hackathon.incident_remediation_agent.jira.JiraDocumentFactory;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;
import com.hackathon.incident_remediation_agent.persistence.IncidentDocumentStore;

import tools.jackson.databind.JsonNode;

class IncidentWorkflowServiceTest {

    private static final String EVIDENCE_VERSION = "abc123";

    private static final String CONTEXT_FILE = "src/main/java/Mapper.java";

    private static final AgentProperties.RepositoryTarget TARGET =
        new AgentProperties.RepositoryTarget("acme/demo-api", "dev", List.of("src/main/java/"), List.of(CONTEXT_FILE));

    private final IncidentAlert alert = new IncidentAlert(
        "01JDEMOEVENT",
        "PINCIDENT",
        "demo-api error rate increased",
        "PDEMO",
        "demo-api",
        Instant.parse("2026-08-14T02:14:00Z"),
        URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));

    private final JiraTicket ticket = new JiraTicket(
        "SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1"));

    private JiraClient jiraClient;
    private JiraDocumentFactory documents;
    private EvidenceCollectionService evidence;
    private AiRouter aiRouter;
    private RepositoryResolver repositories;
    private FixGate gate;
    private GitHubClient gitHubClient;
    private IncidentRunStore store;
    private IncidentDocumentStore incidentDocuments;
    private IncidentWorkflowService workflow;

    @BeforeEach
    void setUp() {
        this.jiraClient = mock(JiraClient.class);
        this.documents = mock(JiraDocumentFactory.class);
        this.evidence = mock(EvidenceCollectionService.class);
        this.aiRouter = mock(AiRouter.class);
        this.repositories = mock(RepositoryResolver.class);
        this.gate = mock(FixGate.class);
        this.gitHubClient = mock(GitHubClient.class);
        this.store = new IncidentRunStore();
        this.incidentDocuments = mock(IncidentDocumentStore.class);
        this.workflow = new IncidentWorkflowService(this.jiraClient, this.documents, this.evidence,
            this.aiRouter, this.repositories, this.gate, this.gitHubClient, this.store,
            this.incidentDocuments);
        this.store.start(this.alert);

        when(this.jiraClient.createIncident(this.alert)).thenReturn(this.ticket);
        when(this.jiraClient.addComment(any(), any())).thenReturn("comment-1");
        when(this.evidence.collect(this.alert, this.ticket)).thenReturn(pack("application_error"));
    }

    @Test
    void publishesEvidenceThenOpensADraftPullRequest() {
        when(this.aiRouter.route(any())).thenReturn(Optional.of(routedFix()));
        when(this.repositories.resolveByName("demo-api")).thenReturn(Optional.of(TARGET));
        when(this.gate.check(anyMap(), anyMap(), eq(TARGET))).thenReturn(List.of(CONTEXT_FILE));
        when(this.gitHubClient.submitFix(any(), any(), eq(TARGET), anyList()))
            .thenReturn(new FixSubmission("agent/incident/SCRUM-1-fix", "sha1",
                List.of(CONTEXT_FILE), new DraftPullRequest(73,
                    URI.create("https://github.com/acme/demo-api/pull/73"))));

        this.workflow.start(storedRun());

        // Evidence reaches Jira before the model is asked for anything.
        verify(this.jiraClient).addComment(eq(this.ticket), any());
        verify(this.jiraClient).updateComment(eq(this.ticket), eq("comment-1"), any());
        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.DRAFT_PR_CREATED);
        assertThat(stored.draftPrUrl())
            .isEqualTo(URI.create("https://github.com/acme/demo-api/pull/73"));
        assertThat(stored.evidenceVersion()).isEqualTo(EVIDENCE_VERSION);
    }

    /** Jira is the first hard gate: nothing downstream may run when it fails. */
    @Test
    void marksRunFailedWhenJiraRejectsTheTicket() {
        when(this.jiraClient.createIncident(this.alert))
            .thenThrow(new IllegalStateException("403 Forbidden"));

        this.workflow.start(storedRun());

        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.FAILED);
        assertThat(stored.message()).contains("403 Forbidden");
        assertThat(stored.jiraKey()).isNull();
        verify(this.jiraClient, never()).addComment(any(JiraTicket.class), any(JsonNode.class));
        verifyNoInteractions(this.evidence, this.aiRouter, this.gitHubClient);
    }

    /** No proposal is a completed investigation, not a failure, and touches no repository. */
    @Test
    void completesWithoutAPullRequestWhenNoFixIsProposed() {
        when(this.evidence.collect(this.alert, this.ticket))
            .thenReturn(pack("infrastructure_or_dependency"));
        when(this.aiRouter.route(any())).thenReturn(Optional.empty());

        this.workflow.start(storedRun());

        verify(this.documents).noCodeAction(any());
        verify(this.jiraClient).updateComment(eq(this.ticket), eq("comment-1"), any());
        verifyNoInteractions(this.gitHubClient);
        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.COMPLETED);
        assertThat(stored.message()).contains("infrastructure_or_dependency");
        assertThat(stored.draftPrUrl()).isNull();
    }

    /** A rejected proposal must reach Jira and must never reach GitHub. */
    @Test
    void completesWithoutAPullRequestWhenTheGateRejectsTheProposal() {
        when(this.aiRouter.route(any())).thenReturn(Optional.of(routedFix()));
        when(this.repositories.resolveByName("demo-api")).thenReturn(Optional.of(TARGET));
        when(this.gate.check(anyMap(), anyMap(), eq(TARGET)))
            .thenThrow(new PatchValidationException("Proposal writes to protected path"));

        this.workflow.start(storedRun());

        verify(this.documents).validationFailureMessage(any(), any());
        verifyNoInteractions(this.gitHubClient);
        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.COMPLETED);
        assertThat(stored.message()).contains("protected path");
    }

    /** With pushing disabled the run still completes, reporting what it would have done. */
    @Test
    void completesWhenPushingIsDisabled() {
        when(this.aiRouter.route(any())).thenReturn(Optional.of(routedFix()));
        when(this.repositories.resolveByName("demo-api")).thenReturn(Optional.of(TARGET));
        when(this.gate.check(anyMap(), anyMap(), eq(TARGET))).thenReturn(List.of(CONTEXT_FILE));
        when(this.gitHubClient.submitFix(any(), any(), eq(TARGET), anyList()))
            .thenReturn(new FixSubmission("agent/incident/SCRUM-1-fix", null,
                List.of(CONTEXT_FILE), null));

        this.workflow.start(storedRun());

        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.COMPLETED);
        assertThat(stored.message()).contains("push is disabled");
        assertThat(stored.draftPrUrl()).isNull();
    }

    /** A repository dropped from the allowlist mid-run must not be written to. */
    @Test
    void refusesToWriteToARepositoryNoLongerAllowlisted() {
        when(this.aiRouter.route(any())).thenReturn(Optional.of(routedFix()));
        when(this.repositories.resolveByName("demo-api")).thenReturn(Optional.empty());

        this.workflow.start(storedRun());

        verifyNoInteractions(this.gitHubClient);
        assertThat(storedRun().stage()).isEqualTo(WorkflowStage.COMPLETED);
        assertThat(storedRun().message()).contains("no longer allowlisted");
    }

    /** The webhook returns 202 immediately, so no external call may happen on its thread. */
    @Test
    void startIsAsync() throws Exception {
        assertThat(IncidentWorkflowService.class.getDeclaredMethod("start", IncidentRun.class))
            .matches(method -> method.isAnnotationPresent(Async.class));
    }

    private RoutedFix routedFix() {
        return new RoutedFix("demo-api",
            new FixProposal(true, "null payload", "Guard the null payload",
                Map.of(CONTEXT_FILE, "class Mapper { /* guarded */ }")),
            Map.of(CONTEXT_FILE, "class Mapper {}"));
    }

    private EvidencePack pack(String classification) {
        return new EvidencePack(this.alert, this.ticket,
            new LogEvidence(143, "NullPointerException", List.of("sample"),
                URI.create("https://splunk.example/app/search")),
            new MetricEvidence(0.4, 6.1, false, URI.create("https://signalfx.example/dashboard"),
                List.of()),
            new DeploymentEvidence("v1.4.2", "abc123",
                URI.create("https://github.com/acme/demo-api/commit/abc123"),
                Instant.parse("2026-08-14T01:55:00Z")),
            null, classification, EVIDENCE_VERSION);
    }

    private IncidentRun storedRun() {
        return this.store.get("PINCIDENT").orElseThrow();
    }
}
