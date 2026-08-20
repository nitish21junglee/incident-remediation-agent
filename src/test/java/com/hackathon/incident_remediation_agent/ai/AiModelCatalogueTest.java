package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

class AiModelCatalogueTest {

    private static final String BASE = "https://ai.example/v1beta/openai";

    private MockRestServiceServer server;

    private AiModelCatalogue catalogue;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.catalogue = new AiModelCatalogue(builder, new AgentProperties(
            "fixture", "PDEMO", null, null, null, null,
            new AgentProperties.Ai(BASE, "secret-key", List.of("gemini-2.5-flash"), Map.of()),
            null));
    }

    @Test
    void listsModelsStrippingTheProviderPrefix() {
        server.expect(requestTo(BASE + "/models"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-key"))
            .andRespond(withSuccess("""
                {"data":[
                  {"id":"models/gemini-2.5-flash"},
                  {"id":"models/gemini-2.5-pro"}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(catalogue.list())
            .extracting(AvailableModel::id)
            .containsExactly("gemini-2.5-flash", "gemini-2.5-pro");
        server.verify();
    }

    /**
     * Image, speech, music and embedding models all appear in the catalogue and would fail as a
     * fix-proposal model, so they are listed but not recommended.
     */
    @Test
    void marksNonTextModelsAsNotRecommended() {
        server.expect(requestTo(BASE + "/models"))
            .andRespond(withSuccess("""
                {"data":[
                  {"id":"models/gemini-2.5-flash"},
                  {"id":"models/gemini-2.5-flash-image"},
                  {"id":"models/gemini-2.5-flash-preview-tts"},
                  {"id":"models/gemini-embedding-001"},
                  {"id":"models/veo-3.1-generate-preview"},
                  {"id":"models/lyria-3-pro-preview"},
                  {"id":"models/gemini-robotics-er-2-preview"},
                  {"id":"models/gemini-2.5-flash-native-audio-latest"}
                ]}
                """, MediaType.APPLICATION_JSON));

        List<AvailableModel> models = catalogue.list();

        assertThat(models).filteredOn(AvailableModel::recommended)
            .extracting(AvailableModel::id)
            .containsExactly("gemini-2.5-flash");
        assertThat(models).hasSize(8);
    }

    @Test
    void sortsAlphabeticallyAndDeduplicates() {
        server.expect(requestTo(BASE + "/models"))
            .andRespond(withSuccess("""
                {"data":[
                  {"id":"models/gemini-2.5-pro"},
                  {"id":"models/gemini-2.5-flash"},
                  {"id":"gemini-2.5-flash"}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(catalogue.list())
            .extracting(AvailableModel::id)
            .containsExactly("gemini-2.5-flash", "gemini-2.5-pro");
    }

    @Test
    void returnsEmptyWhenProviderSendsNoData() {
        server.expect(requestTo(BASE + "/models"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(catalogue.list()).isEmpty();
    }
}
