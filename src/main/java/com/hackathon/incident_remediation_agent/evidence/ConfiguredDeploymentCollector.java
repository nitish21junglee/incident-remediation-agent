package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

/**
 * Reports the deployment recorded in configuration.
 *
 * <p>There is no deployment API in this prototype, so the operator states what is live. The
 * classifier only uses how close {@code deployedAt} is to the incident, so a stale value weakens
 * the "a release caused this" signal rather than inventing one.
 */
@Component
public class ConfiguredDeploymentCollector implements DeploymentCollector {

    private final AgentProperties.Deployment deployment;

    ConfiguredDeploymentCollector(AgentProperties properties) {
        this.deployment = properties.deployment();
    }

    @Override
    public DeploymentEvidence collect(IncidentAlert alert) {
        if (this.deployment == null) {
            return new DeploymentEvidence(null, null, null, null);
        }
        return new DeploymentEvidence(
            this.deployment.version(),
            this.deployment.commitSha(),
            this.deployment.commitUrl() == null ? null : URI.create(this.deployment.commitUrl()),
            this.deployment.deployedAt());
    }
}
