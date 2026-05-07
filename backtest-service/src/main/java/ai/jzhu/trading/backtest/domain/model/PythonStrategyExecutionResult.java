package ai.jzhu.trading.backtest.domain.model;

import java.util.Optional;

/**
 * Result from executing a Python strategy via the runner.
 *
 * @param success      Whether the Python execution succeeded.
 * @param action       The action returned (HOLD / BUY / SELL) if success=true.
 * @param qty          The optional quantity if action is BUY or SELL.
 * @param errorType    Machine-readable error type if success=false (e.g. SYNTAX_ERROR, ENTRYPOINT_NOT_FOUND, EXECUTION_ERROR, INVALID_RETURN).
 * @param errorMessage Human-readable error description if success=false.
 */
public record PythonStrategyExecutionResult(
        boolean success,
        String action,
        Double qty,
        String errorType,
        String errorMessage
) {

    public static PythonStrategyExecutionResult success(String action, Double qty) {
        return new PythonStrategyExecutionResult(true, action, qty, null, null);
    }

    public static PythonStrategyExecutionResult failure(String errorType, String errorMessage) {
        return new PythonStrategyExecutionResult(false, null, null, errorType, errorMessage);
    }

    public boolean isHold() {
        return success && "HOLD".equals(action);
    }

    public boolean isBuy() {
        return success && "BUY".equals(action);
    }

    public boolean isSell() {
        return success && "SELL".equals(action);
    }

    public Optional<Double> getQtyOptional() {
        return Optional.ofNullable(qty);
    }
}