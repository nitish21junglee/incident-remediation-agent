package com.hackathon.incident_remediation_agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "MONGODB_URI=mongodb://mongo-env-proof.invalid:27018/proof",
        "logging.level.org.mongodb.driver=OFF"
    }
)
class MongoEnvironmentConfigurationTest {

    @Autowired
    private MongoClient mongoClient;

    @Test
    void mongodbUriEnvironmentVariableConfiguresTheMongoClient() {
        assertThat(this.mongoClient.getClusterDescription().getClusterSettings().getHosts())
            .containsExactly(new ServerAddress("mongo-env-proof.invalid", 27018));
    }
}
