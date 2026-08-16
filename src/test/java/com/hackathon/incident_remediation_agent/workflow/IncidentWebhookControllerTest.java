package com.hackathon.incident_remediation_agent.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StreamUtils;

import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.incident.PagerDutyPayloadParser;

@WebMvcTest(IncidentWebhookController.class)
@Import({ IncidentRunStore.class, PagerDutyPayloadParser.class })
class IncidentWebhookControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    IncidentWorkflow workflow;

    @Test
    void acceptsTriggeredIncident() throws Exception {
        mockMvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture("PACCEPT")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.incidentId").value("PACCEPT"))
            .andExpect(jsonPath("$.stage").value("RECEIVED"))
            .andExpect(jsonPath("$.duplicate").value(false));

        verify(workflow).start(any(IncidentRun.class));
    }

    @Test
    void deduplicatesRepeatedDelivery() throws Exception {
        mockMvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture("PDEDUPE")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture("PDEDUPE")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.incidentId").value("PDEDUPE"))
            .andExpect(jsonPath("$.duplicate").value(true));

        verify(workflow, times(1)).start(any(IncidentRun.class));
    }

    @Test
    void rejectsUnsupportedEventType() throws Exception {
        String resolved = fixture("PREJECT").replace("incident.triggered", "incident.resolved");

        mockMvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .content(resolved))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                org.hamcrest.Matchers.containsString("incident.triggered")));

        verify(workflow, times(0)).start(any(IncidentRun.class));
    }

    /**
     * {@code IncidentRunStore} is a singleton in a cached context, so each test needs its own
     * incident ID or dedupe state leaks between methods.
     */
    private String fixture(String incidentId) throws Exception {
        return StreamUtils.copyToString(
            new ClassPathResource("pagerduty-incident-triggered.json").getInputStream(),
            StandardCharsets.UTF_8)
            .replace("PINCIDENT", incidentId);
    }
}
