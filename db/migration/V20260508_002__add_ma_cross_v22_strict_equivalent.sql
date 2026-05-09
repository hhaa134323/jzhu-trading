-- Migration: add MA Cross Python v22 with strict Java-equivalent crossover logic
-- See: STAGE_A_AUDIT_REPORT.md pitfall #9 — Python v18/v21 lost cross detection + close confirmation

INSERT INTO strategy_template_version (template_id, version_no, source_kind, definition_json, change_note, created_by)
VALUES (
    'tpl_ma-cross-python',
    22,
    'PYTHON_CODE',
    jsonb_build_object(
        'engineType', 'PYTHON',
        'entrypoint', 'on_bar',
        'baseStrategyId', 'MA Cross (Python)',
        'parameters', jsonb_build_object('closeMaFast', 10, 'closeMaSlow', 20),
        'code', $code$
def on_bar(ctx):
    # MA Cross v22 — strict equivalent of Java MaCrossLongStrategy
    # params: fast, slow (default 10, 20 — same as Java hardcoded)
    # indicators: ma_fast, ma_slow, ma_fast_prev, ma_slow_prev (injected by PythonTradingStrategyAdapter)

    fast = ctx["params"].get("fast", 10)
    slow = ctx["params"].get("slow", 20)

    ma_fast = ctx["indicators"].get("ma_fast")
    ma_slow = ctx["indicators"].get("ma_slow")
    ma_fast_prev = ctx["indicators"].get("ma_fast_prev")
    ma_slow_prev = ctx["indicators"].get("ma_slow_prev")

    # warmup: any indicator value missing → HOLD (equivalent to Java currentIndex < DEFAULT_SLOW_PERIOD)
    if ma_fast is None or ma_slow is None or ma_fast_prev is None or ma_slow_prev is None:
        return {"action": "HOLD"}

    bar_close = ctx["bar"]["close"]
    qty = ctx["position"].get("qty", 0)

    # --- open signal: golden cross + close above slow (same as Java checkOpenSignal) ---
    cross_up = ma_fast_prev <= ma_slow_prev and ma_fast > ma_slow
    close_above_slow = bar_close > ma_slow
    if cross_up and close_above_slow and qty == 0:
        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}

    # --- close signal: death cross OR close below slow (same as Java checkCloseSignal) ---
    cross_down = ma_fast_prev >= ma_slow_prev and ma_fast < ma_slow
    close_below_slow = bar_close < ma_slow
    if (cross_down or close_below_slow) and qty > 0:
        return {"action": "SELL", "qty": qty}

    return {"action": "HOLD"}
$code$
    ),
    'v22: strict equivalent of Java MaCrossLongStrategy (golden cross + death cross + close confirmation)',
    'audit-fix'
);

-- Touch template updated_at
UPDATE strategy_template SET updated_at = now() WHERE template_id = 'tpl_ma-cross-python';
