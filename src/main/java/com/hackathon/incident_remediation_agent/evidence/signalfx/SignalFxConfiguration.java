package com.hackathon.incident_remediation_agent.evidence.signalfx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

/**
 * Exposes the SignalFlow client as a bean so the collector receives it rather than constructing it.
 * The client pins its own base URL from the realm, so injecting it is the only way to point the
 * collector at anything else — which is what makes the collector testable.
 */
@Configuration
public class SignalFxConfiguration {

    @Bean
    SignalFxSignalFlowClient signalFxSignalFlowClient(AgentProperties properties) {
        AgentProperties.SignalFx signalfx = properties.signalfx();
        return new SignalFxSignalFlowClient(signalfx.token(), signalfx.realm());
    }
}
