package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.trading.backtest.domain.model.PythonStrategyExecutionRequest;
import ai.jzhu.trading.backtest.domain.model.PythonStrategyExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes Python strategy code via a subprocess pipe.
 *
 * <p>The runner writes a JSON request to stdin of the Python bridge script,
 * then reads a JSON response from stdout. Timeout and error handling are
 * applied at this boundary.
 *
 * <p>This is NOT a sandbox — it relies on OS-level process isolation only.
 * Full sandboxing (e.g., restricted user, seccomp, gVisor) is out of scope
 * for this step.
 */
@Component
public class PythonStrategyRunner {

    private static final Logger log = LoggerFactory.getLogger(PythonStrategyRunner.class);

    /** Maximum wall-clock time (milliseconds) for a single on_bar call. */
    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    private final ObjectMapper objectMapper;
    private final String pythonCommand;
    private final String bridgeScriptPath;
    private final long timeoutMs;

    public PythonStrategyRunner() {
        this.objectMapper = new ObjectMapper();
        this.pythonCommand = resolvePythonCommand();
        this.bridgeScriptPath = resolveBridgeScriptPath();
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
    }

    // Constructor exposed for testing (timeout injection)
    PythonStrategyRunner(String pythonCommand, String bridgeScriptPath, long timeoutMs) {
        this.objectMapper = new ObjectMapper();
        this.pythonCommand = pythonCommand;
        this.bridgeScriptPath = bridgeScriptPath;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Execute one on_bar(ctx) invocation synchronously.
     *
     * @param code       Python source code.
     * @param entrypoint Function name to call.
     * @param ctx        Context map passed to the function.
     * @return Execution result (success or structured error).
     */
    public PythonStrategyExecutionResult execute(
            String code,
            String entrypoint,
            Map<String, Object> ctx
    ) {
        var request = new PythonStrategyExecutionRequest(code, entrypoint, ctx);
        return execute(request);
    }

    /**
     * Execute one on_bar(ctx) invocation synchronously.
     */
    public PythonStrategyExecutionResult execute(PythonStrategyExecutionRequest request) {
        try {
            // Build the input JSON
            Map<String, Object> input = Map.of(
                    "code", request.code(),
                    "entrypoint", request.entrypoint(),
                    "ctx", request.ctx()
            );
            String inputJson = objectMapper.writeValueAsString(input);

            // Start the Python subprocess
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, bridgeScriptPath);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // Write input to stdin
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(inputJson);
                writer.flush();
            }

            // Read stdout and stderr concurrently BEFORE waitFor to prevent pipe buffer deadlock.
            // If the Python process writes enough output to fill the OS pipe buffer (~64KB on Linux),
            // it will block on write(). If we waitFor() before draining the pipes, both sides hang.
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line);
                    }
                } catch (java.io.IOException ignored) {
                    // stream closed after process exits — expected
                }
            });
            stdoutReader.setDaemon(true);

            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line);
                    }
                } catch (java.io.IOException ignored) {
                    // stream closed after process exits — expected
                }
            });
            stderrReader.setDaemon(true);

            stdoutReader.start();
            stderrReader.start();

            // Wait with timeout (pipes are being drained concurrently)
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            // Ensure readers finish consuming any remaining buffered output
            stdoutReader.join(500);
            stderrReader.join(500);

            if (!finished) {
                process.destroyForcibly();
                log.warn("Python strategy execution timed out after {}ms", timeoutMs);
                return PythonStrategyExecutionResult.failure(
                        "TIMEOUT",
                        "Python strategy execution timed out after " + timeoutMs + "ms"
                );
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && stdout.length() == 0) {
                String stderrMsg = stderr.length() > 0 ? stderr.toString() : "Unknown error (exit code " + exitCode + ")";
                log.warn("Python strategy runner exited with code {}: {}", exitCode, stderrMsg);
                return PythonStrategyExecutionResult.failure("EXECUTION_ERROR", stderrMsg);
            }

            String outputStr = stdout.toString().trim();
            if (outputStr.isEmpty()) {
                return PythonStrategyExecutionResult.failure(
                        "EXECUTION_ERROR",
                        "Python runner produced no output (exit code " + exitCode + ")"
                );
            }

            // Parse JSON response from bridge script
            Map<String, Object> response = objectMapper.readValue(
                    outputStr,
                    new TypeReference<Map<String, Object>>() {
                    }
            );

            boolean bridgeSuccess = Boolean.TRUE.equals(response.get("success"));
            if (!bridgeSuccess) {
                @SuppressWarnings("unchecked")
                Map<String, String> error = (Map<String, String>) response.get("error");
                String errorType = error != null ? error.get("type") : "UNKNOWN";
                String errorMsg = error != null ? error.get("message") : "Unknown error";
                return PythonStrategyExecutionResult.failure(errorType, errorMsg);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            String action = result != null ? (String) result.get("action") : "HOLD";
            Double qty = result != null && result.get("qty") != null
                    ? ((Number) result.get("qty")).doubleValue()
                    : null;

            // Final validation in Java side
            if (action == null || (!action.equals("HOLD") && !action.equals("BUY") && !action.equals("SELL"))) {
                return PythonStrategyExecutionResult.failure(
                        "INVALID_RETURN",
                        "Invalid action: " + action
                );
            }

            return PythonStrategyExecutionResult.success(action, qty);

        } catch (Exception e) {
            log.error("Unexpected error in Python strategy runner", e);
            return PythonStrategyExecutionResult.failure(
                    "EXECUTION_ERROR",
                    "Unexpected runner error: " + e.getMessage()
            );
        }
    }

    /**
     * Resolve the Python command. In Docker container 'python3' is the canonical name.
     */
    private static String resolvePythonCommand() {
        // In CI / Docker environment "python3" is the canonical name.
        // Fallback to "python" for environments where python3 does not exist.
        return "python3";
    }

    /**
     * Locate the bridge script relative to the classpath.
     * When running from a JAR, it will be on the classpath as a resource.
     * When running from IDE / Maven, it will be under target/classes/scripts/.
     */
    static String resolveBridgeScriptPath() {
        // The script is bundled in the JAR under scripts/python_strategy_runner.py
        // We extract it to a temp file to run it via subprocess.
        try {
            java.io.InputStream in = PythonStrategyRunner.class.getClassLoader()
                    .getResourceAsStream("scripts/python_strategy_runner.py");
            if (in == null) {
                throw new IllegalStateException(
                        "scripts/python_strategy_runner.py not found on classpath"
                );
            }
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("python_strategy_runner_", ".py");
            java.nio.file.Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();
            return tempFile.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to locate python_strategy_runner.py", e);
        }
    }
}