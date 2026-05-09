-- Migration: MACD Cross Python v1 — Spec A1 (no Java mirror)
-- User-confirmed spec: fast=12, slow=26, signal=9
-- EMA seed: SMA of first N closes at index N-1, then EMA_t = α*close_t + (1-α)*EMA_{t-1}, α=2/(period+1)

INSERT INTO strategy_template (template_id, name, description, owner_id)
VALUES (
    'tpl_macd-cross-python',
    'MACD Cross (Python)',
    'MACD 金叉死叉策略的 Python 版本（Spec A1）',
    'migration'
);

INSERT INTO strategy_template_version (template_id, version_no, source_kind, definition_json, change_note, created_by)
VALUES (
    'tpl_macd-cross-python',
    1,
    'PYTHON_CODE',
    jsonb_build_object(
        'engineType', 'PYTHON',
        'entrypoint', 'on_bar',
        'baseStrategyId', 'MACD Cross (Python)',
        'parameters', jsonb_build_object(
            'macdFast', 12,
            'macdSlow', 26,
            'macdSignal', 9
        ),
        'code', $code$
def on_bar(ctx):
    # MACD Cross v1 — Spec A1
    # Defaults: fast=12, slow=26, signal=9
    # EMA seed: SMA of first N bars at index N-1; then EMA recurrence α=2/(period+1)
    # Indicators: ema_fast/slow, dif, dea + _prev (injected by adapter, computed from klines)

    fast = ctx["params"].get("fast", 12)
    slow = ctx["params"].get("slow", 26)
    signal_period = ctx["params"].get("signal", 9)

    ema_fast = ctx["indicators"].get("ema_fast")
    ema_slow = ctx["indicators"].get("ema_slow")
    dif = ctx["indicators"].get("dif")
    dea = ctx["indicators"].get("dea")
    dif_prev = ctx["indicators"].get("dif_prev")
    dea_prev = ctx["indicators"].get("dea_prev")
    ema_slow_prev = ctx["indicators"].get("ema_slow_prev")

    curr_close = ctx["bar"]["close"]
    qty = ctx["position"].get("qty", 0)

    # Warmup: any required indicator missing → HOLD
    if dif is None or dea is None or ema_slow is None:
        return {"action": "HOLD"}
    if dif_prev is None or dea_prev is None or ema_slow_prev is None:
        return {"action": "HOLD"}

    # Spec A1 BUY (3 AND, qty==0):
    # BUY-1: cross_up: prev_dif <= prev_dea AND curr_dif > curr_dea
    cross_up = dif_prev <= dea_prev and dif > dea
    # BUY-2: close_above_ema_slow: curr_close > curr_ema_slow
    close_above_ema_slow = curr_close > ema_slow
    if cross_up and close_above_ema_slow and qty == 0:
        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}

    # Spec A1 SELL (2 OR, qty>0):
    # SELL-1: cross_down: prev_dif >= prev_dea AND curr_dif < curr_dea
    cross_down = dif_prev >= dea_prev and dif < dea
    # SELL-2: close_below_ema_slow: curr_close < curr_ema_slow
    close_below_ema_slow = curr_close < ema_slow
    if (cross_down or close_below_ema_slow) and qty > 0:
        return {"action": "SELL", "qty": qty}

    return {"action": "HOLD"}
$code$
    ),
    'v1: MACD Spec A1 (cross_up + close_above_ema_slow; cross_down OR close_below_ema_slow)',
    'migration'
);

UPDATE strategy_template SET updated_at = now() WHERE template_id = 'tpl_macd-cross-python';
