package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model must only ever see files the operator listed. These are the boundary tests for that
 * guarantee.
 */
class RepositoryContextReaderTest {

    private final RepositoryContextReader reader = new RepositoryContextReader();

    @TempDir
    Path root;

    @BeforeEach
    void seedRepository() throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Payment.java"), "class Payment {}");
        Files.writeString(root.resolve("src/main/java/Mapper.java"), "class Mapper {}");
    }

    @Test
    void readsAllowedFilesInConfiguredOrder() {
        Map<String, String> files = reader.read(
            root, List.of("src/main/java/Mapper.java", "src/main/java/Payment.java"));

        assertThat(files).containsExactly(
            Map.entry("src/main/java/Mapper.java", "class Mapper {}"),
            Map.entry("src/main/java/Payment.java", "class Payment {}"));
    }

    @Test
    void rejectsParentTraversal() throws IOException {
        Files.writeString(root.getParent().resolve("secret.txt"), "token");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> reader.read(root, List.of("../secret.txt")))
            .withMessageContaining("../secret.txt");
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> reader.read(root, List.of("/etc/hosts")))
            .withMessageContaining("/etc/hosts");
    }

    @Test
    void rejectsSymlinkEscapingTheRepository() throws IOException {
        Path outside = Files.writeString(root.getParent().resolve("outside.txt"), "token");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        }
        catch (UnsupportedOperationException | IOException exception) {
            return; // filesystem forbids symlinks; the traversal tests still cover the guarantee
        }

        assertThatIllegalArgumentException()
            .isThrownBy(() -> reader.read(root, List.of("link.txt")))
            .withMessageContaining("link.txt");
    }

    @Test
    void rejectsMissingFiles() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> reader.read(root, List.of("src/main/java/Absent.java")))
            .withMessageContaining("Absent.java");
    }

    @Test
    void rejectsTotalContentOverTheCharacterBudget() throws IOException {
        Files.writeString(root.resolve("big.java"), "x".repeat(100_001));

        assertThatIllegalArgumentException()
            .isThrownBy(() -> reader.read(root, List.of("big.java")))
            .withMessageContaining("100000");
    }

    @Test
    void returnsEmptyMapWhenNothingIsConfigured() {
        assertThat(reader.read(root, List.of())).isEmpty();
    }
}
