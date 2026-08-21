package com.hackathon.incident_remediation_agent.git;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;

/**
 * The checks a proposal must survive before anything is written to GitHub.
 *
 * <p>Every rejection is a {@link PatchValidationException}: an expected outcome that ends the run as
 * a completed investigation with no pull request, not an infrastructure failure.
 *
 * <p>Without a checkout there is no {@code git apply} to prove the model was looking at the real
 * file, so the shape checks here carry that weight instead. The size guard is the important one: a
 * model asked to patch one line can return a truncated rewrite of the whole file, and the diff
 * would look plausible until someone read it.
 */
@Component
public class FixGate {

    /** Reject a rewrite that loses more than this fraction of the original file. */
    private static final double MAX_SHRINK = 0.5;

    private final int maxChangedFiles;
    private final List<String> protectedPaths;

    FixGate(AgentProperties properties) {
        AgentProperties.GitHub github = properties.github();
        this.maxChangedFiles = github.maxChangedFiles() > 0 ? github.maxChangedFiles() : 5;
        this.protectedPaths = github.protectedPaths() == null ? List.of() : github.protectedPaths();
    }

    /**
     * @param originals the content the model was given, for the size comparison
     * @return the changed paths, in a stable order, once every check has passed
     * @throws PatchValidationException on the first failing check
     */
    public List<String> check(
        Map<String, String> proposed,
        Map<String, String> originals,
        AgentProperties.RepositoryTarget target
    ) {
        if (proposed == null || proposed.isEmpty()) {
            throw new PatchValidationException("The model returned no file changes");
        }
        if (proposed.size() > this.maxChangedFiles) {
            throw new PatchValidationException("Proposal changes %d files, the limit is %d"
                .formatted(proposed.size(), this.maxChangedFiles));
        }

        for (Map.Entry<String, String> file : proposed.entrySet()) {
            String path = file.getKey();
            String content = file.getValue();

            if (!isWritable(path, target)) {
                throw new PatchValidationException(
                    "Proposal writes to %s, which is outside the repository's writable paths"
                        .formatted(path));
            }
            if (isProtected(path)) {
                throw new PatchValidationException("Proposal writes to protected path " + path);
            }
            if (!originals.containsKey(path)) {
                // Files are chosen per incident now, so the writable paths alone no longer say
                // which ones the model saw. It cannot have rewritten a file it was never given.
                throw new PatchValidationException(
                    "Proposal writes to %s, which the model was not given".formatted(path));
            }
            if (content == null || content.isBlank()) {
                throw new PatchValidationException("Proposal empties " + path);
            }

            String original = originals.get(path);
            if (content.length() < original.length() * MAX_SHRINK) {
                throw new PatchValidationException(
                    "Proposal shrinks %s from %d to %d characters, which looks like a truncated "
                        .formatted(path, original.length(), content.length())
                        + "rewrite rather than a fix");
            }
            if (content.equals(original)) {
                throw new PatchValidationException("Proposal leaves " + path + " unchanged");
            }
        }
        return List.copyOf(proposed.keySet());
    }

    private boolean isProtected(String path) {
        return this.protectedPaths.stream().anyMatch(path::startsWith);
    }

    private static boolean isWritable(String path, AgentProperties.RepositoryTarget target) {
        List<String> writable = target.writablePaths() == null ? List.of() : target.writablePaths();
        return writable.stream().anyMatch(path::startsWith);
    }
}
