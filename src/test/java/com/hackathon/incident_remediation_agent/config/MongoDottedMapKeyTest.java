package com.hackathon.incident_remediation_agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.persistence.IncidentDocument;

/**
 * Every key in {@link FixProposal#files()} is a repository path, so every key holds a dot, and
 * Mongo refuses a dotted key outright. Without the escape the workflow investigates, opens the
 * draft pull request, and then fails on the write that records what it did.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "MONGODB_URI=mongodb://dotted-key-proof.invalid:27017/proof",
        "logging.level.org.mongodb.driver=OFF"
    }
)
class MongoDottedMapKeyTest {

    private static final String PATH =
        "src/main/java/com/flutter/reward_service/service/impl/RewardServiceImpl.java";

    private static final String CONTENT = "class RewardServiceImpl { /* guarded */ }";

    @Autowired
    private MappingMongoConverter converter;

    @Test
    void storesAndRestoresAFilePathUsedAsAMapKey() {
        Document stored = new Document();
        this.converter.write(IncidentDocument.received("PSLACK1787326875")
            .aiOutput(new FixProposal(true, "the payload is null", "Guard the null payload",
                Map.of(PATH, CONTENT))), stored);

        IncidentDocument read = this.converter.read(IncidentDocument.class, stored);

        // The underscores in reward_service have to survive the round trip untouched too.
        assertThat(read.aiOutput().files()).containsExactly(Map.entry(PATH, CONTENT));
    }
}
