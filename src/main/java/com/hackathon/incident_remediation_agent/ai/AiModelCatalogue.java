package com.hackathon.incident_remediation_agent.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

import tools.jackson.databind.JsonNode;

/**
 * Lists the models the configured key can actually reach, so the operator picks from reality
 * rather than typing a name that may not exist.
 *
 * <p>Uses the configured base URL's {@code /models}, which every OpenAI-compatible provider
 * exposes, so this keeps working if the provider changes.
 */
@Component
public class AiModelCatalogue {

    /**
     * Substrings marking a model whose output is not text. The provider reports these as
     * generate-capable, but they would fail as a fix-proposal model. Edit as providers add
     * modalities.
     */
    private static final List<String> NON_TEXT_MARKERS = List.of(
        "-tts", "-image", "native-audio", "-live", "embedding", "veo-", "lyria-",
        "robotics", "nano-banana", "computer-use", "translate", "aqa");

    private final RestClient restClient;

    AiModelCatalogue(RestClient.Builder builder, AgentProperties properties) {
        AgentProperties.Ai ai = properties.ai();
        this.restClient = builder
            .baseUrl(ai.baseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ai.apiKey())
            .build();
    }

    public List<AvailableModel> list() {
        JsonNode response = this.restClient.get()
            .uri("/models")
            .retrieve()
            .body(JsonNode.class);

        // TreeSet gives sorting and de-duplication in one step.
        TreeSet<String> ids = new TreeSet<>();
        JsonNode data = response == null ? null : response.get("data");
        if (data != null) {
            for (JsonNode model : data) {
                JsonNode id = model.get("id");
                if (id != null && id.isString() && !id.asString().isBlank()) {
                    ids.add(id.asString().replaceFirst("^models/", ""));
                }
            }
        }

        List<AvailableModel> models = new ArrayList<>();
        ids.forEach(id -> models.add(new AvailableModel(id, isTextCapable(id))));
        return List.copyOf(models);
    }

    private static boolean isTextCapable(String id) {
        String lower = id.toLowerCase();
        return NON_TEXT_MARKERS.stream().noneMatch(lower::contains);
    }
}
