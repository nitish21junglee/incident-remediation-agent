package com.hackathon.incident_remediation_agent.dashboard;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hackathon.incident_remediation_agent.incident.WorkflowStage;
import com.hackathon.incident_remediation_agent.persistence.IncidentDocument;
import com.hackathon.incident_remediation_agent.persistence.IncidentDocumentRepository;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    private final IncidentDocumentRepository repository;

    DashboardController(IncidentDocumentRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/incidents")
    List<IncidentDocument> incidents() {
        return this.repository.findAll()
            .stream()
            .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
            .toList();
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        List<IncidentDocument> all = this.repository.findAll();

        Map<WorkflowStage, Long> statusCounts = all.stream()
            .collect(Collectors.groupingBy(IncidentDocument::status, Collectors.counting()));

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
}
