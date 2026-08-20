package com.hackathon.incident_remediation_agent.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Reads the operator-listed repository files that may be sent to the model.
 *
 * <p>This is the only place repository content enters a prompt, so it is the enforcement point for
 * "only configured files leave the machine". Every rejection is an {@link IllegalArgumentException}
 * rather than a silent skip, so a misconfiguration fails loudly instead of quietly shrinking the
 * context.
 */
@Component
public class RepositoryContextReader {

    private static final int MAX_TOTAL_CHARACTERS = 100_000;

    /**
     * @param allowedFiles repository-relative paths, in the order they should reach the prompt
     * @return insertion-ordered map of path to file content
     * @throws IllegalArgumentException on traversal, absolute paths, symlinks leaving the
     *                                 repository, missing files, or content over the budget
     */
    public Map<String, String> read(Path repositoryRoot, List<String> allowedFiles) {
        Path root = canonicalRoot(repositoryRoot);
        Map<String, String> files = new LinkedHashMap<>();
        int total = 0;

        for (String candidate : allowedFiles) {
            Path resolved = resolveWithinRoot(root, candidate);
            String content = readString(resolved, candidate);

            total += content.length();
            if (total > MAX_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException(
                    "Repository context exceeds %d characters at %s"
                        .formatted(MAX_TOTAL_CHARACTERS, candidate));
            }
            files.put(candidate, content);
        }
        return files;
    }

    /**
     * Canonicalises the root so symlinked temp directories (macOS {@code /var} to
     * {@code /private/var}) do not make every legitimate file look like an escape.
     */
    private static Path canonicalRoot(Path repositoryRoot) {
        try {
            return repositoryRoot.toRealPath();
        }
        catch (IOException exception) {
            throw new IllegalArgumentException(
                "Repository root does not exist: " + repositoryRoot, exception);
        }
    }

    private static Path resolveWithinRoot(Path root, String candidate) {
        Path relative = Path.of(candidate);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Context file must be repository-relative: " + candidate);
        }

        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Context file escapes the repository: " + candidate);
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Context file does not exist: " + candidate);
        }

        // normalize() cannot see through a symlink, so the real path is checked separately.
        Path real = toRealPath(resolved, candidate);
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("Context file escapes the repository: " + candidate);
        }
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Context file is not a regular file: " + candidate);
        }
        return real;
    }

    private static Path toRealPath(Path resolved, String candidate) {
        try {
            return resolved.toRealPath();
        }
        catch (IOException exception) {
            throw new IllegalArgumentException("Context file does not exist: " + candidate, exception);
        }
    }

    private static String readString(Path file, String candidate) {
        try {
            return Files.readString(file);
        }
        catch (IOException exception) {
            throw new UncheckedIOException("Cannot read context file " + candidate, exception);
        }
    }
}
