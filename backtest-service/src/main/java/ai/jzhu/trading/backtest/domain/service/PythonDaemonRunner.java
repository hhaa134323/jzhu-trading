package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.trading.backtest.domain.model.PythonStrategyExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Long-running Python daemon for strategy execution.
 *
 * <p>Replaces the per-bar ProcessBuilder pattern with a single fork per backtest.
 * One {@code init} message at startup compiles the strategy code; subsequent
 * {@code bar} messages invoke the entrypoint in the pre-compiled namespace.
 *
 * <p>Lifecycle: one instance per backtest. Call {@link #close()} after the
 * backtest completes (normal or exceptional) to shut down the daemon process.
 *
 * <p>B0 defense: if {@link #onBar} returns {@code success=false} for
 * {@value #MAX_CONSECUTIVE_FAILURES} consecutive bars, a {@link RuntimeException}
 * is thrown to surface the error at the top level instead of silently
 * downgrading to HOLD.
 */
public class PythonDaemonRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PythonDaemonRunner.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final ObjectMapper objectMapper;
    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final Thread stderrDrain;
    private int consecutiveFailures;

    /**
     * Create a daemon, spawn the Python process, and send the init handshake.
     *
     * @param code       Python strategy source code.
     * @param entrypoint Function name to call per bar (default "on_bar").
     * @throws RuntimeException if the daemon fails to start or init reports an error.
     */
    public PythonDaemonRunner(String code, String entrypoint) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        String ep = entrypoint != null && !entrypoint.isBlank() ? entrypoint : "on_bar";

        this.objectMapper = new ObjectMapper();
        this.consecutiveFailures = 0;

        String pythonCommand = "python3";
        String scriptPath = resolveDaemonScriptPath();

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, scriptPath);
            pb.redirectErrorStream(false);
            this.process = pb.start();

            this.stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            // Concurrent stderr drain to prevent pipe buffer deadlock
            this.stderrDrain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.warn("Python daemon stderr: {}", line);
                    }
                } catch (java.io.IOException ignored) {
                    // stream closed after process exit
                }
            });
            stderrDrain.setDaemon(true);
            stderrDrain.setName("python-daemon-stderr");
            stderrDrain.start();

            // Send init handshake
            Map<String, Object> initMsg = Map.of(
                    "type", "init",
                    "code", code,
                    "entrypoint", ep
            );
            writeLine(objectMapper.writeValueAsString(initMsg));

            // Read init response
            String responseLine = readLine();
            if (responseLine == null) {
                throw new RuntimeException(
                        "Python daemon: no init response (process may have crashed)");
            }
            Map<String, Object> response = objectMapper.readValue(
                    responseLine,
                    new TypeReference<Map<String, Object>>() {}
            );
            String respType = (String) response.get("type");
            if ("init_error".equals(respType)) {
                String error = (String) response.get("error");
                throw new RuntimeException("Python strategy failed to init: " + error);
            }
            if (!"init_ok".equals(respType)) {
                throw new RuntimeException(
                        "Python daemon: unexpected init response type: " + respType);
            }

            log.info("Python daemon started: entrypoint={}", ep);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Python daemon: " + e.getMessage(), e);
        }
    }

    /**
     * Execute one on_bar(ctx) invocation via the daemon.
     *
     * @param ctx Context map (params, indicators, position, bar).
     * @return Execution result.
     */
    public PythonStrategyExecutionResult onBar(Map<String, Object> ctx) {
        try {
            Map<String, Object> barMsg = Map.of(
                    "type", "bar",
                    "ctx", ctx
            );
            writeLine(objectMapper.writeValueAsString(barMsg));

            String responseLine = readLine();
            if (responseLine == null) {
                consecutiveFailures++;
                String errMsg = "Python daemon: no response (process may have crashed), "
                        + "consecutive failures=" + consecutiveFailures;
                log.error(errMsg);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw new RuntimeException(
                            "Python strategy failed " + consecutiveFailures
                            + " consecutive bars: daemon unresponsive");
                }
                return PythonStrategyExecutionResult.failure("EXECUTION_ERROR", errMsg);
            }

            Map<String, Object> response = objectMapper.readValue(
                    responseLine,
                    new TypeReference<Map<String, Object>>() {}
            );
            String respType = (String) response.get("type");

            if ("bar_ok".equals(respType)) {
                consecutiveFailures = 0;
                String action = (String) response.get("action");
                Double qty = response.get("qty") != null
                        ? ((Number) response.get("qty")).doubleValue()
                        : null;

                // Final validation (paranoid — daemon also validates, but belt+suspenders)
                if (action == null || (!action.equals("HOLD") && !action.equals("BUY") && !action.equals("SELL"))) {
                    return PythonStrategyExecutionResult.failure(
                            "INVALID_RETURN", "Invalid action: " + action);
                }
                return PythonStrategyExecutionResult.success(action, qty);
            }

            // bar_error or unknown type
            consecutiveFailures++;
            String error = (String) response.getOrDefault("error", "Unknown daemon error");
            log.warn("Python daemon bar error (consecutive={}/{}): {}",
                    consecutiveFailures, MAX_CONSECUTIVE_FAILURES, error);

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw new RuntimeException(
                        "Python strategy failed " + consecutiveFailures
                        + " consecutive bars: " + error);
            }

            return PythonStrategyExecutionResult.failure("EXECUTION_ERROR", error);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            consecutiveFailures++;
            log.error("Python daemon communication error (consecutive={}/{})",
                    consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e);
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw new RuntimeException(
                        "Python strategy failed " + consecutiveFailures
                        + " consecutive bars: " + e.getMessage(), e);
            }
            return PythonStrategyExecutionResult.failure(
                    "EXECUTION_ERROR", "Daemon communication error: " + e.getMessage());
        }
    }

    /**
     * Shut down the daemon process cleanly.
     */
    @Override
    public void close() {
        try {
            writeLine(objectMapper.writeValueAsString(Map.of("type", "shutdown")));
            stdin.close();
        } catch (Exception ignored) {
            // daemon may already be dead
        }
        try {
            boolean terminated = process.waitFor(3, TimeUnit.SECONDS);
            if (!terminated) {
                process.destroyForcibly();
                log.warn("Python daemon did not exit gracefully, destroyed");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            stderrDrain.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        log.info("Python daemon closed");
    }

    private void writeLine(String json) throws java.io.IOException {
        stdin.write(json);
        stdin.write('\n');
        stdin.flush();
    }

    private String readLine() throws java.io.IOException {
        return stdout.readLine();
    }

    /**
     * Resolve the daemon script path.
     *
     * <p>The script is bundled as a classpath resource (src/main/resources/python/).
     * At runtime it is extracted to a temp file, same pattern as the existing
     * python_strategy_runner.py bridge script.
     */
    private static String resolveDaemonScriptPath() {
        try {
            java.io.InputStream in = PythonDaemonRunner.class.getClassLoader()
                    .getResourceAsStream("python/strategy_daemon.py");
            if (in == null) {
                throw new IllegalStateException(
                        "python/strategy_daemon.py not found on classpath");
            }
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(
                    "strategy_daemon_", ".py");
            java.nio.file.Files.copy(in, tempFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();
            return tempFile.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to locate strategy_daemon.py", e);
        }
    }
}
