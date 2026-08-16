package com.hackathon.incident_remediation_agent.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the application context runnable until Task 6 supplies the real
 * {@code IncidentWorkflowService}. Delete this class then.
 */
@Configuration
public class WorkflowConfiguration {

    @Bean
    @ConditionalOnMissingBean(IncidentWorkflow.class)
    IncidentWorkflow noOpIncidentWorkflow() {
        return run -> { };
    }
}
