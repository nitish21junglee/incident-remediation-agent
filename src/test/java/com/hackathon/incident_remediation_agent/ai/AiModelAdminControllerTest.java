package com.hackathon.incident_remediation_agent.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

@WebMvcTest(AiModelAdminController.class)
@Import(AiModelAdminControllerTest.Config.class)
class AiModelAdminControllerTest {

    @TestConfiguration
    static class Config {

        @Bean
        AgentProperties agentProperties() {
            return new AgentProperties("fixture", "PDEMO", null, null, null, null,
                new AgentProperties.Ai("https://ai.example", "key",
                    List.of("flash"), Map.of("fix-proposal", List.of("pro"))),
                null);
        }

        @Bean
        AiModelSelector aiModelSelector(AgentProperties properties) {
            return new AiModelSelector(properties);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AiModelCatalogue catalogue;

    @Test
    void listsAvailableModelsForThePicker() throws Exception {
        org.mockito.Mockito.when(catalogue.list()).thenReturn(List.of(
            new AvailableModel("gemini-2.5-flash", true),
            new AvailableModel("veo-3.1-generate-preview", false)));

        mockMvc.perform(get("/admin/ai/models/available"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("gemini-2.5-flash"))
            .andExpect(jsonPath("$[0].recommended").value(true))
            .andExpect(jsonPath("$[1].recommended").value(false));
    }

    @Test
    void reportsTheEffectiveChains() throws Exception {
        mockMvc.perform(get("/admin/ai/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['fix-proposal']").value("pro"));
    }

    @Test
    void overridesATaskChain() throws Exception {
        mockMvc.perform(put("/admin/ai/models/fix-proposal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"models\":[\"gemini-2.5-pro\",\"gemini-2.5-flash\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['fix-proposal'][0]").value("gemini-2.5-pro"))
            .andExpect(jsonPath("$.['fix-proposal'][1]").value("gemini-2.5-flash"));

        mockMvc.perform(get("/admin/ai/models"))
            .andExpect(jsonPath("$.['fix-proposal'][0]").value("gemini-2.5-pro"));
    }

    @Test
    void clearingAnOverrideRestoresConfiguration() throws Exception {
        mockMvc.perform(put("/admin/ai/models/fix-proposal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"models\":[\"experimental\"]}"))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/admin/ai/models/fix-proposal"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/ai/models"))
            .andExpect(jsonPath("$.['fix-proposal']").value("pro"));
    }

    @Test
    void rejectsUnknownTaskWithNotFound() throws Exception {
        mockMvc.perform(put("/admin/ai/models/not-a-task")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"models\":[\"flash\"]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void rejectsEmptyChainWithBadRequest() throws Exception {
        mockMvc.perform(put("/admin/ai/models/fix-proposal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"models\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsImplausibleModelNameWithBadRequest() throws Exception {
        mockMvc.perform(put("/admin/ai/models/fix-proposal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"models\":[\"pro; rm -rf /\"]}"))
            .andExpect(status().isBadRequest());
    }
}
