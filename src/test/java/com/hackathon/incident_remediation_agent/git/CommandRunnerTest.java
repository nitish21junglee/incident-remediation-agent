package com.hackathon.incident_remediation_agent.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRunnerTest {

    private final CommandRunner runner = new CommandRunner();

    @TempDir
    Path directory;

    /** Java itself is the one subprocess guaranteed to exist wherever these tests run. */
    @Test
    void runsACommandAndCapturesOutput() {
        CommandResult result = runner.run(
            List.of("java", "-version"), directory, Duration.ofSeconds(30), null);

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        // The JDK writes -version to stderr, so assert on the pair rather than one stream.
        assertThat(result.stdout() + result.stderr()).contains("version");
    }

    @Test
    void reportsNonZeroExitCodes() {
        CommandResult result = runner.run(
            List.of("java", "--not-a-real-flag"), directory, Duration.ofSeconds(30), null);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void killsCommandsThatExceedTheTimeout() {
        // A shell is acceptable in the test fixture; production code never invokes one.
        CommandResult result = runner.run(
            List.of("sh", "-c", "sleep 5"), directory, Duration.ofMillis(300), null);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void writesStdinToTheProcess() {
        CommandResult result = runner.run(
            List.of("sh", "-c", "cat"), directory, Duration.ofSeconds(10), "patch content\n");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("patch content");
    }

    @Test
    void runsInTheGivenDirectory() {
        CommandResult result = runner.run(
            List.of("sh", "-c", "pwd"), directory, Duration.ofSeconds(10), null);

        assertThat(result.stdout().trim()).endsWith(directory.getFileName().toString());
    }

    @Test
    void reportsMissingExecutablesWithoutThrowing() {
        CommandResult result = runner.run(
            List.of("definitely-not-a-real-binary-xyz"), directory, Duration.ofSeconds(5), null);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).isNotBlank();
    }

    @Test
    void capsCapturedOutput() {
        CommandResult result = runner.run(
            List.of("sh", "-c", "yes abcdefghij | head -c 200000"),
            directory, Duration.ofSeconds(30), null);

        assertThat(result.stdout().length()).isLessThanOrEqualTo(100_000);
    }
}
