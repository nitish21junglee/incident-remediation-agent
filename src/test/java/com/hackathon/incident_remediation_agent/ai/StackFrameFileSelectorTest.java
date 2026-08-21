package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;

/**
 * The samples here are the shape {@code HttpSplunkCollector} produces: the exception signature,
 * then the origin frame, then the entry-point frame.
 */
class StackFrameFileSelectorTest {

    private static final String WRITABLE = "src/main/java/com/flutter/reward_service/";

    private static final AgentProperties.RepositoryTarget TARGET =
        new AgentProperties.RepositoryTarget(
            "Flutter-Global/darsrftp-service", "dev", List.of("src/main/java/"), List.of(WRITABLE));

    private final StackFrameFileSelector selector = new StackFrameFileSelector();

    /** The incident that motivated this: the fault was in a file configuration never named. */
    @Test
    void picksTheFilesTheFramesNameNearestFirst() {
        List<String> selected = selector.select(logs(
            "java.lang.ArithmeticException: / by zero",
            "\tat com.flutter.reward_service.service.impl.RewardServiceImpl.getPopup(RewardServiceImpl.java:296)",
            "\tat com.flutter.reward_service.controller.RewardController.getPopup(RewardController.java:49)"),
            TARGET);

        assertThat(selected).containsExactly(
            WRITABLE + "service/impl/RewardServiceImpl.java",
            WRITABLE + "controller/RewardController.java");
    }

    /** Frames repeat across every sampled event; the same file must not be sent twice. */
    @Test
    void reportsEachFileOnce() {
        String frame =
            "\tat com.flutter.reward_service.service.impl.RewardServiceImpl.getPopup(RewardServiceImpl.java:296)";

        assertThat(selector.select(logs(frame, frame, frame), TARGET))
            .containsExactly(WRITABLE + "service/impl/RewardServiceImpl.java");
    }

    /** Whoever can write a log line can write a frame, so the prefix list is what bounds this. */
    @Test
    void ignoresFramesOutsideTheWritablePaths() {
        assertThat(selector.select(logs(
            "\tat org.springframework.web.servlet.DispatcherServlet.doGet(DispatcherServlet.java:1072)",
            "\tat java.base/java.lang.Thread.run(Thread.java:1583)",
            "\tat com.someone.else.Exfiltrate.run(Exfiltrate.java:1)"),
            TARGET)).isEmpty();
    }

    /** An inner class or lambda frame names the outer file, which is the one on disk. */
    @Test
    void mapsAnInnerClassFrameToItsOuterFile() {
        assertThat(selector.select(logs(
            "\tat com.flutter.reward_service.service.KafkaConsumer$1.onMessage(KafkaConsumer.java:88)"),
            TARGET)).containsExactly(WRITABLE + "service/KafkaConsumer.java");
    }

    @Test
    void returnsNothingWhenThereAreNoFrames() {
        assertThat(selector.select(logs("GET /rs/v1/rewards/popup failed in 1ms"), TARGET)).isEmpty();
        assertThat(selector.select(new LogEvidence(0, null, null, null), TARGET)).isEmpty();
        assertThat(selector.select(null, TARGET)).isEmpty();
    }

    /** A prefix that is the source root itself still has to resolve the package underneath it. */
    @Test
    void acceptsASourceRootAsTheWritablePath() {
        AgentProperties.RepositoryTarget root = new AgentProperties.RepositoryTarget(
            "Flutter-Global/darsrftp-service", "dev", List.of("src/main/java/"), List.of("src/main/java/"));

        assertThat(selector.select(logs(
            "\tat com.flutter.reward_service.controller.RewardController.getPopup(RewardController.java:49)"),
            root)).containsExactly(WRITABLE + "controller/RewardController.java");
    }

    private static LogEvidence logs(String... samples) {
        return new LogEvidence(samples.length, samples[0], List.of(samples), null);
    }
}
