package com.hackathon.incident_remediation_agent.dashboard;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.evidence.SignalFxExport;
import com.hackathon.incident_remediation_agent.incident.WorkflowStage;
import com.hackathon.incident_remediation_agent.persistence.IncidentDocument;
import com.hackathon.incident_remediation_agent.persistence.IncidentDocumentRepository;

/**
 * Documents are upserted incrementally as a workflow runs (see {@code IncidentDocumentStore}), and
 * some in the collection predate fields added later or were written outside the normal workflow
 * (e.g. spike/debug scripts) — so {@code timestamp} and {@code status} are treated as possibly
 * {@code null} throughout, rather than assumed present because the record type says non-null.
 */
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    private final IncidentDocumentRepository repository;

    DashboardController(IncidentDocumentRepository repository) {
        this.repository = repository;
    }

    /**
     * List view, newest first, undated documents last. Trims each export's {@code rawPoints} —
     * that's a per-program JSON blob of every point SignalFx returned, sized for one incident's
     * detail view, not for shipping on every row of every poll.
     */
    @GetMapping("/incidents")
    List<IncidentDocument> incidents() {
        return this.repository.findAll()
            .stream()
            .map(DashboardController::withoutRawPoints)
            .sorted(Comparator.comparing(IncidentDocument::timestamp,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @GetMapping("/incidents/{incidentId}")
    ResponseEntity<IncidentDocument> incident(@PathVariable String incidentId) {
        return this.repository.findById(incidentId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        List<IncidentDocument> all = this.repository.findAll();

        Map<WorkflowStage, Long> statusCounts = all.stream()
            .map(IncidentDocument::status)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(status -> status, Collectors.counting()));

        long total = all.size();
        long completed = statusCounts.getOrDefault(WorkflowStage.COMPLETED, 0L);
        long failed = statusCounts.getOrDefault(WorkflowStage.FAILED, 0L);
        long active = total - completed - failed;

        return Map.of(
            "total", total,
            "completed", completed,
            "failed", failed,
            "active", active,
            "pipeline", statusCounts);
    }

    private static IncidentDocument withoutRawPoints(IncidentDocument doc) {
        MetricEvidence metrics = doc.signalFxExports();
        if (metrics == null || metrics.exports() == null) {
            return doc;
        }

        List<SignalFxExport> trimmedExports = metrics.exports().stream()
            .map(export -> new SignalFxExport(export.program(), export.filter(), export.pointCount(),
                export.before(), export.during(), null, export.error()))
            .toList();

        MetricEvidence trimmedMetrics = new MetricEvidence(metrics.errorRateBefore(),
            metrics.errorRateDuring(), metrics.latencyChanged(), metrics.dashboardUrl(), trimmedExports);

        return new IncidentDocument(doc.incidentId(), doc.jiraKey(), doc.jiraUrl(), doc.splunkLogs(),
            trimmedMetrics, doc.lastPullRequest(), doc.revertPullRequest(), doc.fixPullRequest(),
            doc.aiOutput(), doc.timestamp(), doc.status());
    }
}
