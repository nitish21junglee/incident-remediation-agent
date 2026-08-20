package com.hackathon.incident_remediation_agent.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Runs an external command without a shell.
 *
 * <p>The command is always passed to {@link ProcessBuilder} as an argument list, so no part of it is
 * ever evaluated by a shell. Model-proposed text therefore cannot become a command. stdout and
 * stderr are drained concurrently to avoid the classic pipe-buffer deadlock, and each is capped so a
 * runaway build cannot exhaust the heap.
 */
@Component
public class CommandRunner {

    private static final int MAX_CAPTURED_CHARACTERS = 100_000;

    public CommandResult run(List<String> command, Path directory, Duration timeout, String stdin) {
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
        }
        catch (IOException exception) {
            // A missing executable is an expected outcome here, not an infrastructure failure.
            return new CommandResult(-1, "", String.valueOf(exception.getMessage()), false);
        }

        ExecutorService drains = Executors.newFixedThreadPool(2);
        try {
            Future<String> out = drains.submit(() -> drain(process.getInputStream()));
            Future<String> err = drains.submit(() -> drain(process.getErrorStream()));
            writeStdin(process, stdin);

            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
                return new CommandResult(-1, get(out), get(err), true);
            }
            return new CommandResult(process.exitValue(), get(out), get(err), false);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(-1, "", "interrupted", false);
        }
        finally {
            drains.shutdownNow();
        }
    }

    private static void writeStdin(Process process, String stdin) {
        try (OutputStream sink = process.getOutputStream()) {
            if (stdin != null) {
                sink.write(stdin.getBytes(StandardCharsets.UTF_8));
                sink.flush();
            }
        }
        catch (IOException exception) {
            // The process may already have exited; its exit code is the meaningful signal.
        }
    }

    private static String drain(InputStream stream) throws IOException {
        try (stream) {
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return content.length() <= MAX_CAPTURED_CHARACTERS
                ? content
                : content.substring(0, MAX_CAPTURED_CHARACTERS);
        }
    }

    private static String get(Future<String> stream) {
        try {
            return stream.get();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
        catch (ExecutionException exception) {
            return "";
        }
    }
}
