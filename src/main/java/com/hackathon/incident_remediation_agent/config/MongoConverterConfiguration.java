package com.hackathon.incident_remediation_agent.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

/**
 * Lets a repository path be used as a map key.
 *
 * <p>Every key in {@code FixProposal.files()} is a path, so every key holds a dot, and Mongo
 * rejects a dotted key outright. Spring Data escapes them once a replacement is set, and restores
 * them on read, so the application still sees the real path.
 *
 * <p>Separate from {@link MongoConfiguration} on purpose. The converter is built on the database
 * factory, which is built on the client, which is built with the customizer that
 * {@code MongoConfiguration} declares: asking for the converter there is a circular reference the
 * context refuses to start with.
 */
@Configuration
public class MongoConverterConfiguration {

    /**
     * A fullwidth full stop, U+FF0E. The restore replaces every occurrence, so the stand-in has to
     * be a character no path can contain: {@code _} would turn {@code reward_service} back into
     * {@code reward.service}.
     */
    private static final String DOT_REPLACEMENT = "．";

    @Autowired
    void escapeDottedMapKeys(MappingMongoConverter converter) {
        converter.setMapKeyDotReplacement(DOT_REPLACEMENT);
    }
}
