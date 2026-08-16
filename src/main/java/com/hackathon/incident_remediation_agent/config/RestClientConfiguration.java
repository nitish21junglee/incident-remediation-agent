package com.hackathon.incident_remediation_agent.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the 10-second connect/read timeouts to every auto-configured
 * {@code RestClient.Builder}.
 *
 * <p>Adapters must inject {@code RestClient.Builder} and call {@code .baseUrl(...)} in their own
 * constructor. The auto-configured builder is prototype-scoped, so each adapter receives its own
 * instance and cannot corrupt another adapter's base URL.
 */
@Configuration
public class RestClientConfiguration {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClientCustomizer timeoutRestClientCustomizer() {
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect()
            .build(HttpClientSettings.defaults().withTimeouts(TIMEOUT, TIMEOUT)));
    }
}
