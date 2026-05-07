package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.trading.backtest.domain.model.PythonStrategyExecutionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PythonStrategyRunner} — validates Python code execution,
 * entrypoint resolution, action validation, and error handling.
 *
 * <p>These tests require Python 3 to be available on the system.
 * In the CI/Docker environment, the backtest-service Docker image
 * (jzhu-backtest-service:latest) includes python3.
 */
class PythonStrategyRunnerTest {

    private static PythonStrategyRunner runner;

    @BeforeAll
    static void setUp() {
        runner = new PythonStrategyRunner();
    }

    // ==================== Happy path ====================

    @Test
    void testHoldStrategy() {
        String code = """
                def on_bar(ctx):
                    return {"action": "HOLD"}
                """;

        Map<String, Object> ctx = Map.of(
                "params", Map.of(),
                "indicators", Map.of(),
                "position", Map.of("qty", 0),
                "bar", Map.of("symbol", "AAPL", "close", 100.0, "timestamp", "2024-01-02")
        );

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", ctx);

        assertTrue(result.success(), "HOLD should succeed");
        assertEquals("HOLD", result.action());
        assertNull(result.qty(), "HOLD should have no qty");
    }

    @Test
    void testBuyStrategy() {
        String code = """
                def on_bar(ctx):
                    if ctx["position"].get("qty", 0) == 0:
                        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}
                    return {"action": "HOLD"}
                """;

        Map<String, Object> ctx = Map.of(
                "params", Map.of("qty", 100),
                "indicators", Map.of(),
                "position", Map.of("qty", 0),
                "bar", Map.of("close", 100.0)
        );

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", ctx);

        assertTrue(result.success(), "BUY should succeed");
        assertEquals("BUY", result.action());
        assertEquals(100.0, result.qty(), 0.001);
    }

    @Test
    void testSellStrategy() {
        String code = """
                def on_bar(ctx):
                    if ctx["position"].get("qty", 0) > 0:
                        return {"action": "SELL", "qty": ctx["position"]["qty"]}
                    return {"action": "HOLD"}
                """;

        Map<String, Object> ctx = Map.of(
                "params", Map.of(),
                "indicators", Map.of(),
                "position", Map.of("qty", 100),
                "bar", Map.of("close", 100.0)
        );

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", ctx);

        assertTrue(result.success(), "SELL should succeed");
        assertEquals("SELL", result.action());
        assertEquals(100.0, result.qty(), 0.001);
    }

    // ==================== MA Cross strategy ====================

    @Test
    void testMaCrossBuy() {
        String code = """
                def on_bar(ctx):
                    fast = ctx["params"].get("fast", 5)
                    slow = ctx["params"].get("slow", 20)
                    ma_fast = ctx["indicators"].get("ma_fast")
                    ma_slow = ctx["indicators"].get("ma_slow")
                    if ma_fast is None or ma_slow is None:
                        return {"action": "HOLD"}
                    if ma_fast > ma_slow and ctx["position"].get("qty", 0) == 0:
                        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}
                    if ma_fast < ma_slow and ctx["position"].get("qty", 0) > 0:
                        return {"action": "SELL", "qty": ctx["position"]["qty"]}
                    return {"action": "HOLD"}
                """;

        // Case A: ma_fast > ma_slow and no position → BUY
        Map<String, Object> ctx = Map.of(
                "params", Map.of("fast", 5, "slow", 20, "qty", 100),
                "indicators", Map.of("ma_fast", 10.5, "ma_slow", 9.8),
                "position", Map.of("qty", 0),
                "bar", Map.of("close", 100.0)
        );

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", ctx);

        assertTrue(result.success(), "MA Cross BUY should succeed");
        assertEquals("BUY", result.action());
        assertEquals(100.0, result.qty(), 0.001);
    }

    @Test
    void testMaCrossSell() {
        String code = """
                def on_bar(ctx):
                    fast = ctx["params"].get("fast", 5)
                    slow = ctx["params"].get("slow", 20)
                    ma_fast = ctx["indicators"].get("ma_fast")
                    ma_slow = ctx["indicators"].get("ma_slow")
                    if ma_fast is None or ma_slow is None:
                        return {"action": "HOLD"}
                    if ma_fast > ma_slow and ctx["position"].get("qty", 0) == 0:
                        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}
                    if ma_fast < ma_slow and ctx["position"].get("qty", 0) > 0:
                        return {"action": "SELL", "qty": ctx["position"]["qty"]}
                    return {"action": "HOLD"}
                """;

        // Case B: ma_fast < ma_slow and has position → SELL
        Map<String, Object> ctx = Map.of(
                "params", Map.of("fast", 5, "slow", 20, "qty", 100),
                "indicators", Map.of("ma_fast", 9.5, "ma_slow", 10.2),
                "position", Map.of("qty", 100),
                "bar", Map.of("close", 100.0)
        );

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", ctx);

        assertTrue(result.success(), "MA Cross SELL should succeed");
        assertEquals("SELL", result.action());
        assertEquals(100.0, result.qty(), 0.001);
    }

    @Test
    void testMaCrossHold() {
        String code = """
                def on_bar(ctx):
                    fast = ctx["params"].get("fast", 5)
                    slow = ctx["params"].get("slow", 20)
                    ma_fast = ctx["indicators"].get("ma_fast")
                    ma_slow = ctx["indicators"].get("ma_slow")
                    if ma_fast is None or ma_slow is None:
                        return {"action": "HOLD"}
                    if ma_fast > ma_slow and ctx["position"].get("qty", 0) == 0:
                        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}
                    if ma_fast < ma_slow and ctx["position"].get("qty", 0) > 0:
                        return {"action": "SELL", "qty": ctx["position"]["qty"]}
                    return {"action": "HOLD"}
                """;

        // Case C: no indicators available → HOLD
        Map<String, Object> ctx = Map.of(
                "params", Map.of("fast", 5, "slow", 20, "qty", 100),
                "indicators", Map.of(),
                "position", Map.of("qty", 0),
                "bar", Map.of("close", 100.0)
        );

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", ctx);

        assertTrue(result.success(), "MA Cross HOLD should succeed");
        assertEquals("HOLD", result.action());
    }

    // ==================== Error paths ====================

    @Test
    void testSyntaxError() {
        String code = """
                def on_bar(ctx)
                    return {"action": "HOLD"}
                """;

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", Map.of());

        assertFalse(result.success(), "Syntax error should fail");
        assertEquals("SYNTAX_ERROR", result.errorType());
        assertNotNull(result.errorMessage());
        System.out.println("SYNTAX_ERROR message: " + result.errorMessage());
    }

    @Test
    void testEntrypointNotFound() {
        String code = """
                def other(ctx):
                    return {"action": "HOLD"}
                """;

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", Map.of());

        assertFalse(result.success(), "Missing entrypoint should fail");
        assertEquals("ENTRYPOINT_NOT_FOUND", result.errorType());
        assertNotNull(result.errorMessage());
        System.out.println("ENTRYPOINT_NOT_FOUND message: " + result.errorMessage());
    }

    @Test
    void testInvalidAction() {
        String code = """
                def on_bar(ctx):
                    return {"action": "INVALID"}
                """;

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", Map.of());

        assertFalse(result.success(), "Invalid action should fail");
        assertEquals("INVALID_RETURN", result.errorType());
    }

    @Test
    void testNonDictReturn() {
        String code = """
                def on_bar(ctx):
                    return "BUY"
                """;

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", Map.of());

        assertFalse(result.success(), "Non-dict return should fail");
        assertEquals("INVALID_RETURN", result.errorType());
    }

    @Test
    void testBuyWithNegativeQty() {
        String code = """
                def on_bar(ctx):
                    return {"action": "BUY", "qty": -1}
                """;

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", Map.of());

        assertFalse(result.success(), "Negative qty should fail");
        assertEquals("INVALID_RETURN", result.errorType());
    }

    @Test
    void testTimeout() {
        String code = """
                def on_bar(ctx):
                    while True:
                        pass
                """;

        // Use a short timeout to make the test fast
        PythonStrategyRunner fastRunner = new PythonStrategyRunner("python3",
                PythonStrategyRunner.resolveBridgeScriptPath(),
                500 // 500ms timeout
        );

        PythonStrategyExecutionResult result = fastRunner.execute(code, "on_bar", Map.of());

        assertFalse(result.success(), "Timeout should fail");
        assertEquals("TIMEOUT", result.errorType());
    }

    @Test
    void testBuyWithNonNumericQty() {
        // qty must be a number for validation; the bridge script catches this
        String code = """
                def on_bar(ctx):
                    return {"action": "BUY", "qty": "lots"}
                """;

        PythonStrategyExecutionResult result = runner.execute(code, "on_bar", Map.of());

        // The bridge script handles non-numeric qty as INVALID_RETURN
        assertFalse(result.success(), "Non-numeric qty should fail");
        assertEquals("INVALID_RETURN", result.errorType());
    }
}