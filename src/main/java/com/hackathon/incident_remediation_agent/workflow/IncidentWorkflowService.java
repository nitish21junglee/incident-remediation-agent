package com.hackathon.incident_remediation_agent.workflow;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.hackathon.incident_remediation_agent.ai.AiRouter;
import com.hackathon.incident_remediation_agent.ai.RoutedFix;
import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.EvidenceCollectionService;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
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
import com.hackathon.incident_remediation_agent.slack.SlackNotifier;

/**
 * Runs the incident investigation off the webhook thread.
 *
 * <p>Two hard gates, in order. Jira must exist before anything else happens, so every run leaves a
 * trace a human can find. The evidence must be published to Jira before a model sees repository
 * content, so no code is ever sent anywhere without the reason being written down first.
 *
 * <p>Three outcomes are all successes, not failures: no fix proposed, a fix rejected by the gates,
 * and a draft pull request opened. Only an unexpected external failure marks the run failed.
 */
@Service
public class IncidentWorkflowService implements IncidentWorkflow {

    private static final Logger log = LoggerFactory.getLogger(IncidentWorkflowService.class);

    private final JiraClient jiraClient;
    private final JiraDocumentFactory documents;
    private final EvidenceCollectionService evidence;
    private final AiRouter aiRouter;
    private final RepositoryResolver repositories;
    private final FixGate gate;
    private final GitHubClient gitHubClient;
    private final IncidentRunStore store;
    private final IncidentDocumentStore incidentDocuments;
    private final SlackNotifier slack;

    IncidentWorkflowService(JiraClient jiraClient, JiraDocumentFactory documents,
        EvidenceCollectionService evidence, AiRouter aiRouter, RepositoryResolver repositories,
        FixGate gate, GitHubClient gitHubClient, IncidentRunStore store,
        IncidentDocumentStore incidentDocuments, SlackNotifier slack) {
        this.jiraClient = jiraClient;
        this.documents = documents;
        this.evidence = evidence;
        this.aiRouter = aiRouter;
        this.repositories = repositories;
        this.gate = gate;
        this.gitHubClient = gitHubClient;
        this.store = store;
        this.incidentDocuments = incidentDocuments;
        this.slack = slack;
    }

    @Override
    @Async
    public void start(IncidentRun run) {
        IncidentAlert alert = run.alert();
        this.incidentDocuments.start(alert.incidentId());
        this.slack.incidentReceived(alert);

        JiraTicket ticket;
        try {
            ticket = this.jiraClient.createIncident(alert);
            this.store.update(alert.incidentId(), current -> current.jiraCreated(ticket.key()));
            this.incidentDocuments.update(alert.incidentId(),
                current -> current.jiraCreated(ticket.key(), ticket.browseUrl()));
            log.info("Created Jira ticket {} ({}) for incident {}",
                ticket.key(), ticket.browseUrl(), alert.incidentId());
            this.slack.jiraCreated(alert, ticket.key(), ticket.browseUrl());
        }
        catch (RuntimeException exception) {
            log.error("Jira ticket creation failed for incident {}; abandoning run",
                alert.incidentId(), exception);
            this.store.update(alert.incidentId(),
                current -> current.failed("jira ticket creation failed: " + exception.getMessage()));
            this.incidentDocuments.update(alert.incidentId(),
                current -> current.status(WorkflowStage.FAILED));
            this.slack.workflowFailed(alert, "Jira ticket creation failed");
            return;
        }

        try {
            investigate(alert, ticket);
        }
        catch (RuntimeException exception) {
            log.error("Investigation failed for incident {}", alert.incidentId(), exception);
            this.store.update(alert.incidentId(),
                current -> current.failed("investigation failed: " + exception.getMessage()));
            this.incidentDocuments.update(alert.incidentId(),
                current -> current.status(WorkflowStage.FAILED));
            this.slack.workflowFailed(alert, exception.getMessage());
        }
    }

    private void investigate(IncidentAlert alert, JiraTicket ticket) {
        this.store.update(alert.incidentId(), IncidentRun::collectingContext);
        EvidencePack pack = this.evidence.collect(alert, ticket);
        this.incidentDocuments.update(alert.incidentId(),
            current -> current.signalFxExports(pack.metrics())
                .lastPullRequest(pack.change() == null ? null : pack.change().lastPullRequest()));

        String commentId = this.jiraClient.addComment(ticket, this.documents.contextComment(pack));
        this.store.update(alert.incidentId(),
            current -> current.contextPublished(commentId, pack.evidenceVersion()));
        log.info("Published {} evidence for {} to {}",
            pack.classification(), alert.incidentId(), ticket.key());
        this.slack.evidenceCollected(alert, pack.classification());

        this.store.update(alert.incidentId(), IncidentRun::investigating);
        this.incidentDocuments.update(alert.incidentId(),
            current -> current.status(WorkflowStage.AI_INVESTIGATING));
        Optional<RoutedFix> routed = this.aiRouter.route(pack);
        if (routed.isEmpty()) {
            this.jiraClient.updateComment(ticket, commentId, this.documents.noCodeAction(pack));
            this.store.update(alert.incidentId(),
                current -> current.completed("no code change proposed: " + pack.classification()));
            this.incidentDocuments.update(alert.incidentId(),
                current -> current.status(WorkflowStage.COMPLETED));
            this.slack.noCodeAction(alert, pack.classification());
            return;
        }

        submit(alert, ticket, commentId, pack, routed.get());
    }

    private void submit(IncidentAlert alert, JiraTicket ticket, String commentId, EvidencePack pack,
        RoutedFix routed) {

        this.store.update(alert.incidentId(), IncidentRun::validating);
        this.incidentDocuments.update(alert.incidentId(),
            current -> current.status(WorkflowStage.VALIDATING));
        try {
            AgentProperties.RepositoryTarget target =
                this.repositories.resolveByName(routed.repositoryName())
                    .orElseThrow(() -> new PatchValidationException(
                        "Repository " + routed.repositoryName() + " is no longer allowlisted"));

            List<String> changedFiles = this.gate.check(
                routed.proposal().files(), routed.originalFiles(), target);
            requireCurrentEvidence(alert, pack);

            FixSubmission submission =
                this.gitHubClient.submitFix(pack, routed.proposal(), target, changedFiles);

            this.jiraClient.updateComment(ticket, commentId,
                this.documents.finalPrMessage(pack, routed.proposal(), submission));

            WorkflowStage finalStatus =
                submission.pushed() ? WorkflowStage.DRAFT_PR_CREATED : WorkflowStage.COMPLETED;
            this.incidentDocuments.update(alert.incidentId(),
                current -> current.aiOutput(routed.proposal()).status(finalStatus));

            if (submission.pushed()) {
                this.store.update(alert.incidentId(),
                    current -> current.draftPrCreated(submission.pullRequest().url()));
                this.slack.fixProposed(alert, submission.pullRequest().url());
            }
            else {
                this.store.update(alert.incidentId(),
                    current -> current.completed("fix prepared but push is disabled"));
                this.slack.noCodeAction(alert, "fix prepared but push is disabled");
            }
        }
        catch (PatchValidationException exception) {
            log.info("Proposed fix for {} rejected: {}", alert.incidentId(), exception.getMessage());
            this.jiraClient.updateComment(ticket, commentId,
                this.documents.validationFailureMessage(routed.proposal(), exception));
            this.store.update(alert.incidentId(),
                current -> current.completed("fix rejected: " + exception.getMessage()));
            this.incidentDocuments.update(alert.incidentId(),
                current -> current.aiOutput(routed.proposal()).status(WorkflowStage.COMPLETED));
            this.slack.fixRejected(alert, exception.getMessage());
        }
    }

    /**
     * Refuses to write anything if the evidence behind the proposal is no longer what the run holds.
     * Checked as late as possible, immediately before the only call that can create a branch.
     */
    private void requireCurrentEvidence(IncidentAlert alert, EvidencePack pack) {
        String current = this.store.get(alert.incidentId())
            .map(IncidentRun::evidenceVersion)
            .orElse(null);
        if (!pack.evidenceVersion().equals(current)) {
            throw new PatchValidationException(
                "Evidence changed during the investigation; expected %s but the run holds %s"
                    .formatted(pack.evidenceVersion(), current));
        }
    }
}
