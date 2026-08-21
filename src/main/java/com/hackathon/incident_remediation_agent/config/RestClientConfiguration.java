package com.hackathon.incident_remediation_agent.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the default connect/read timeouts to every auto-configured
 * {@code RestClient.Builder}.
 *
 * <p>Adapters must inject {@code RestClient.Builder} and call {@code .baseUrl(...)} in their own
 * constructor. The auto-configured builder is prototype-scoped, so each adapter receives its own
 * instance and cannot corrupt another adapter's base URL.
 */
@Configuration
public class RestClientConfiguration {

    /** Connecting is not the slow part; an endpoint that will not answer should fail fast. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Ten seconds was too short for a real incident. The log endpoint answers in well under a
     * second from a developer machine and repeatedly took longer than ten from the deployed
     * container, which cost whole investigations: no logs means no stack frames, and no stack
     * frames means nothing to send a model. Every call here runs off the webhook thread, so
     * waiting costs latency on an async investigation and nothing else. {@code RestAiFixClient}
     * raises its own read timeout further still, because whole-file replies take minutes.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    RestClientCustomizer timeoutRestClientCustomizer() {
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect()
            .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)));
    }
}
