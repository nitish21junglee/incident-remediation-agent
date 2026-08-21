package com.hackathon.incident_remediation_agent;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe for the hosting platform's health check and for the keep-warm ping that stops a
 * free-tier instance from spinning down. Deliberately does no work: it must answer while an
 * incident run is in flight.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
