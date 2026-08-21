package com.hackathon.incident_remediation_agent.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the five-minute connect/read timeouts to every auto-configured
 * {@code RestClient.Builder}.
 *
 * <p>Adapters must inject {@code RestClient.Builder} and call {@code .baseUrl(...)} in their own
 * constructor. The auto-configured builder is prototype-scoped, so each adapter receives its own
 * instance and cannot corrupt another adapter's base URL.
 */
@Configuration
public class RestClientConfiguration {

    /**
     * Five minutes on both connect and read, for every adapter behind this builder.
     *
     * <p>Ten seconds was too short for a real incident: the log endpoint answers in well under a
     * second from a developer machine and repeatedly took longer than ten from the deployed
     * container, so the fetch was cancelled mid-flight and the run collected nothing. That is not
     * a small loss, because no logs means no stack frames and no stack frames means no files.
     *
     * <p>Every call behind this builder runs off the webhook thread, so waiting costs latency on
     * an async investigation and nothing else. The trade is deliberate: an unreachable host now
     * holds a run open for five minutes rather than failing fast.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    @Bean
    RestClientCustomizer timeoutRestClientCustomizer() {
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect()
            .build(HttpClientSettings.defaults().withTimeouts(TIMEOUT, TIMEOUT)));
    }
}
