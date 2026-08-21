package com.hackathon.incident_remediation_agent.config;

import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;

/**
 * Pins the auto-configured {@code MongoClient} to Stable API v1, matching the client Atlas hands
 * out in its own connection snippet.
 *
 * <p>A customizer rather than a {@code MongoClient} bean on purpose: defining the client outright
 * would displace the auto-configured {@code MongoDatabaseFactory} and {@code MongoTemplate} that
 * {@code IncidentDocumentRepository} is built on. The connection string stays in
 * {@code spring.mongodb.uri}, so there is still exactly one place it is declared.
 *
 * <p>Strict mode is deliberately left off, so any Spring Data command outside the v1 command set
 * keeps working instead of being rejected by the server.
 */
@Configuration
public class MongoConfiguration {

    private static final ServerApi STABLE_V1 = ServerApi.builder()
        .version(ServerApiVersion.V1)
        .build();

    @Bean
    MongoClientSettingsBuilderCustomizer serverApiCustomizer() {
        return builder -> builder.serverApi(STABLE_V1);
    }
}
