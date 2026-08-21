package com.hackathon.incident_remediation_agent.slack;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.workflow.IncidentWorkflow;

@WebMvcTest(SlackEventController.class)
@Import(IncidentRunStore.class)
@EnableConfigurationProperties(AgentProperties.class)
class SlackEventControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    IncidentWorkflow workflow;

    @Test
    void handlesUrlVerificationChallenge() throws Exception {
        String body = """
            {"type":"url_verification","challenge":"abc123xyz"}
            """;

        mockMvc.perform(post("/slack/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.challenge").value("abc123xyz"));
    }

    @Test
    void triggersWorkflowFromPagerDutyMessage() throws Exception {
        mockMvc.perform(post("/slack/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture("PSLACK001")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.incidentId").value("PSLACK001"))
            .andExpect(jsonPath("$.triggered").value(true));

        verify(workflow).start(any(IncidentRun.class));
    }

    @Test
    void deduplicatesRepeatedMessages() throws Exception {
        mockMvc.perform(post("/slack/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture("PSLACKDUP")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triggered").value(true));

        mockMvc.perform(post("/slack/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture("PSLACKDUP")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triggered").value(false));

        verify(workflow, times(1)).start(any(IncidentRun.class));
    }

    @Test
    void ignoresMessageWithoutPagerDutyLink() throws Exception {
        String body = """
            {"type":"event_callback","event":{"type":"message","channel":"C_INCIDENTS","text":"just a normal message","ts":"1723601640.000100"}}
            """;

        mockMvc.perform(post("/slack/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(workflow, times(0)).start(any(IncidentRun.class));
    }

    @Test
    void ignoresNonMessageEvents() throws Exception {
        String body = """
            {"type":"event_callback","event":{"type":"reaction_added","channel":"C_INCIDENTS"}}
            """;

        mockMvc.perform(post("/slack/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(workflow, times(0)).start(any(IncidentRun.class));
    }

    private String fixture(String incidentId) throws Exception {
        return StreamUtils.copyToString(
            new ClassPathResource("slack-pagerduty-message.json").getInputStream(),
            StandardCharsets.UTF_8)
            .replace("PSLACK001", incidentId);
    }
}
