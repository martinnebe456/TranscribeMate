package com.transcribemate.v2.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/*
BackendClient is responsible for managing the lifecycle and communication with the backend server process. 
It starts the backend, sends requests, receives responses and events, and handles shutdown. 
Communication is done via JSON messages over the backend's standard input and output streams. 
The client also provides a simple event listener mechanism for handling asynchronous events from the backend.
*/
public final class BackendClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final String workDir;
    private final Object writeLock = new Object();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();

    private Process process;
    private BufferedWriter stdin;
    private ExecutorService ioExecutor;
    private volatile boolean closing;

    private BackendClient(List<String> command, String workDir) {
        this.command = command;
        this.workDir = workDir;
    }

    public static BackendClient auto() {
        Path backendRoot = AppRuntimePaths.resolveBackendRoot();
        Path projectRoot = AppRuntimePaths.resolveProjectRoot();
        String workDir = backendRoot == null ? null : backendRoot.toString();

        String customCommand = System.getenv("TM_BACKEND_CMD");
        List<String> cmd = new ArrayList<>();
        if (customCommand != null && !customCommand.isBlank()) {
            for (String part : customCommand.trim().split("\\s+")) {
                if (!part.isBlank()) {
                    cmd.add(part);
                }
            }
        } else {
            String python = System.getenv("TM_BACKEND_PYTHON");
            if (python == null || python.isBlank()) {
                python = detectProjectRuntimePython(projectRoot);
            }
            if (python == null || python.isBlank()) {
                python = detectManagedRuntimePython();
            }
            if (python == null || python.isBlank()) {
                python = "python";
            }
            if (workDir != null && !workDir.isBlank()) {
                // Embedded Python runtime uses isolated mode via python*._pth,
                // so imports from backend source must be injected explicitly.
                cmd.add(python);
                cmd.add("-c");
                cmd.add(
                        "import os, sys, runpy\n"
                                + "root = os.environ.get('TM_BACKEND_ROOT')\n"
                                + "if root and root not in sys.path:\n"
                                + "    sys.path.insert(0, root)\n"
                                + "runpy.run_module('transcribemate.v2.backend.server', run_name='__main__')"
                );
            } else {
                cmd.add(python);
                cmd.add("-m");
                cmd.add("transcribemate.v2.backend.server");
                cmd.add("--stdio");
            }
        }

        return new BackendClient(cmd, workDir);
    }

    private static String detectProjectRuntimePython(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }

        List<Path> candidates = new ArrayList<>();
        candidates.add(projectRoot.resolve(".venv").resolve("Scripts").resolve("python.exe"));
        candidates.add(projectRoot.resolve(".venv").resolve("bin").resolve("python3"));
        candidates.add(projectRoot.resolve(".venv").resolve("bin").resolve("python"));

        for (Path candidate : candidates) {
            try {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            } catch (Exception ignored) {
                // Continue with next candidate.
            }
        }
        return null;
    }

    private static String detectManagedRuntimePython() {
        List<Path> candidates = new ArrayList<>();
        candidates.addAll(AppRuntimePaths.managedRuntimePythonCandidates(AppRuntimePaths.resolveAppDataDir()));

        for (Path candidate : candidates) {
            try {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            } catch (Exception ignored) {
                // Continue with next candidate.
            }
        }
        return null;
    }

    public void start() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        AppFileLogger.log("INFO", "backend.client", "Starting backend process.");

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null && !workDir.isBlank()) {
            pb.directory(new java.io.File(workDir));
            pb.environment().putIfAbsent("TM_BACKEND_ROOT", workDir);
        }
        pb.redirectErrorStream(false);
        pb.environment().putIfAbsent("PYTHONUTF8", "1");
        pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

        process = pb.start();
        AppFileLogger.log("INFO", "backend.client", "Backend process started (pid=" + process.pid() + ").");
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        ioExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("tm-v2-backend-io");
            return t;
        });

        ioExecutor.submit(() -> readStdout(process));
        ioExecutor.submit(() -> readStderr(process));
    }

    public void addEventListener(Consumer<JsonNode> listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(Consumer<JsonNode> listener) {
        eventListeners.remove(listener);
    }

    public CompletableFuture<JsonNode> sendRequest(String method) {
        return sendRequest(method, MAPPER.createObjectNode());
    }

    public CompletableFuture<JsonNode> sendRequest(String method, ObjectNode params) {
        if (process == null || !process.isAlive()) {
            CompletableFuture<JsonNode> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Backend process is not running."));
            return failed;
        }

        String id = UUID.randomUUID().toString();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("type", "request");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? MAPPER.createObjectNode() : params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            String line = MAPPER.writeValueAsString(request);
            synchronized (writeLock) {
                stdin.write(line);
                stdin.write("\n");
                stdin.flush();
            }
        } catch (Exception ex) {
            pending.remove(id);
            future.completeExceptionally(ex);
            AppFileLogger.logException("backend.client.send_request", ex);
        }

        return future;
    }

    public JsonNode sendRequestBlocking(String method, ObjectNode params, Duration timeout) throws Exception {
        CompletableFuture<JsonNode> future = sendRequest(method, params);
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void readStdout(Process proc) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleServerLine(line);
            }
        } catch (Exception ex) {
            AppFileLogger.logException("backend.client.stdout", ex);
            publishSyntheticEvent("backend.io_error", "stdout", ex.toString());
        } finally {
            failPending("Backend stdout closed.");
        }
    }

    private void readStderr(Process proc) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                AppFileLogger.log("DEBUG", "backend.stderr", line);
                publishSyntheticEvent("backend.stderr", "stderr", line);
            }
        } catch (Exception ex) {
            AppFileLogger.logException("backend.client.stderr", ex);
            publishSyntheticEvent("backend.io_error", "stderr", ex.toString());
        }
    }

    private void handleServerLine(String line) {
        try {
            JsonNode message = MAPPER.readTree(line);
            String type = message.path("type").asText("");
            if ("response".equals(type)) {
                String id = message.path("id").asText("");
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    boolean ok = message.path("ok").asBoolean(false);
                    if (ok) {
                        future.complete(message.path("result"));
                    } else {
                        JsonNode errorNode = message.path("error");
                        String msg = errorNode.path("message").asText("Unknown backend error");
                        future.completeExceptionally(new RuntimeException(msg));
                    }
                }
                return;
            }

            if ("event".equals(type)) {
                for (Consumer<JsonNode> listener : eventListeners) {
                    listener.accept(message);
                }
            }
        } catch (Exception ex) {
            AppFileLogger.logException("backend.client.protocol", ex);
            publishSyntheticEvent("backend.protocol_error", "stdout", ex.toString() + " | line=" + line);
        }
    }

    private void publishSyntheticEvent(String event, String source, String message) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("type", "event");
        payload.put("event", event);
        ObjectNode inner = payload.putObject("payload");
        inner.put("source", source);
        inner.put("message", message == null ? "" : message);

        for (Consumer<JsonNode> listener : eventListeners) {
            listener.accept(payload);
        }
    }

    private void failPending(String reason) {
        String safeReason = reason == null ? "Backend request channel closed." : reason;
        if (closing) {
            AppFileLogger.log("INFO", "backend.client", safeReason);
            pending.clear();
            return;
        }

        AppFileLogger.log("WARN", "backend.client", safeReason);
        for (Map.Entry<String, CompletableFuture<JsonNode>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException(safeReason));
        }
        pending.clear();
    }

    @Override
    public void close() {
        closing = true;
        try {
            if (process != null && process.isAlive()) {
                try {
                    sendRequest("shutdown");
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Ignore graceful shutdown errors.
                }
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
            }
            failPending("Backend client closed.");
        }
    }
}
