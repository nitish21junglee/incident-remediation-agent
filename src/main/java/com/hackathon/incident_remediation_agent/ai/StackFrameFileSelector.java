package com.hackathon.incident_remediation_agent.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;

/**
 * Chooses which repository files go in front of the model, from the stack frames in the log
 * evidence.
 *
 * <p>This replaces a configured list of files. A fixed list can only ever describe one incident:
 * every other incident is handed the same files, the model is asked about code unrelated to the
 * failure, and it correctly declines. The frames are the only part of the evidence that names the
 * faulting file, so they are what picks it.
 *
 * <p>Frames are untrusted input — whoever can write a log line can write a frame — so a derived
 * path is kept only when it falls under one of the repository's configured writable paths. That
 * list, not the log, is what bounds where the agent can reach.
 */
@Component
public class StackFrameFileSelector {

    private static final Logger log = LoggerFactory.getLogger(StackFrameFileSelector.class);

    /**
     * A JVM stack frame: the declaring class, then the source file and line in parentheses. The
     * declaring class carries the package; the parenthesised name carries the file, which is the
     * outer class for an inner class or a lambda and so is the one that maps to a path.
     */
    private static final Pattern FRAME =
        Pattern.compile("at\\s+([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+\\.java):\\d+\\)");

    /** Bound on prompt size. {@code GitHubContextReader} enforces the real character budget. */
    private static final int MAX_FILES = 5;

    /**
     * @return repository-relative paths in the order the frames named them, nearest frame first
     */
    public List<String> select(LogEvidence logs, AgentProperties.RepositoryTarget target) {
        if (logs == null || logs.samples() == null) {
            return List.of();
        }

        Set<String> paths = new LinkedHashSet<>();
        for (String line : logs.samples()) {
            Matcher matcher = FRAME.matcher(line);
            while (matcher.find()) {
                sourceFile(matcher.group(1), matcher.group(2))
                    .flatMap(relative -> underWritablePath(relative, target))
                    .ifPresent(paths::add);
            }
        }

        List<String> selected = paths.stream().limit(MAX_FILES).toList();
        log.info("Stack frames named {} file(s) inside {}; sending {}",
            paths.size(), target.slug(), selected);
        return selected;
    }

    /**
     * @return the file's path relative to its source root, for example
     *         {@code com/flutter/reward_service/service/impl/RewardServiceImpl.java}
     */
    private static Optional<String> sourceFile(String declaringClass, String fileName) {
        int lastDot = declaringClass.lastIndexOf('.');
        if (lastDot < 0) {
            // Default package. Nothing to build a directory from, and nothing we ship lives there.
            return Optional.empty();
        }
        return Optional.of(declaringClass.substring(0, lastDot).replace('.', '/') + '/' + fileName);
    }

    /**
     * Turns a source-root-relative path into a repository path under one of the configured source
     * roots, and keeps it only if the result is somewhere the agent may write.
     */
    private static Optional<String> underWritablePath(
        String sourceFile, AgentProperties.RepositoryTarget target) {

        return list(target.sourceRoots()).stream()
            .map(root -> root.endsWith("/") ? root + sourceFile : root + "/" + sourceFile)
            .filter(path -> list(target.writablePaths()).stream().anyMatch(path::startsWith))
            .findFirst();
    }

    private static List<String> list(List<String> configured) {
        return configured == null ? List.of() : configured;
    }
}
