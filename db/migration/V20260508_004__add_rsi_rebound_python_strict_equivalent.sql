-- Migration: RSI Rebound (Bollinger Mean Reversion) Python v1
-- Strict equivalent of Java BollReversionLongStrategy (getId()="bollReversionLong")

INSERT INTO strategy_template (template_id, name, description, owner_id)
VALUES (
    'tpl_rsi-rebound-python',
    'RSI Rebound (Python)',
    '布林带均值回归 + RSI 超卖反弹策略的 Python 版本（等价 BollReversionLongStrategy）',
    'migration'
);

INSERT INTO strategy_template_version (template_id, version_no, source_kind, definition_json, change_note, created_by)
VALUES (
    'tpl_rsi-rebound-python',
    1,
    'PYTHON_CODE',
    jsonb_build_object(
        'engineType', 'PYTHON',
        'entrypoint', 'on_bar',
        'baseStrategyId', 'bollReversionLong',
        'parameters', jsonb_build_object(
            'closeMaFast', 20,
            'closeMaSlow', 20,
            'pullbackMaPeriod', 12
        ),
        'code', $code$
def on_bar(ctx):
    # RSI Rebound (Bollinger Mean Reversion) v1
    # Strict equivalent of Java BollReversionLongStrategy
    # Constants: boll_window=20, k=2.0, rsi_period=12, rsi_buy_threshold=30
    # Indicators: boll_lower/upper/mid + _prev, rsi_12, close_prev (injected by adapter)

    boll_lower = ctx["indicators"].get("boll_lower")
    boll_lower_prev = ctx["indicators"].get("boll_lower_prev")
    boll_mid = ctx["indicators"].get("boll_mid")
    boll_upper = ctx["indicators"].get("boll_upper")
    rsi_12 = ctx["indicators"].get("rsi_12")
    close_prev = ctx["indicators"].get("close_prev")

    curr_close = ctx["bar"]["close"]
    qty = ctx["position"].get("qty", 0)

    # Warmup: need boll_lower_prev, boll_lower, boll_mid, close_prev
    if boll_lower_prev is None or boll_lower is None or boll_mid is None or close_prev is None:
        return {"action": "HOLD"}

    # BUY (3-part AND, qty==0):
    # 1. reenteredBand: prev close < prev lower AND curr close >= curr lower
    reentered_band = close_prev < boll_lower_prev and curr_close >= boll_lower
    # 2. reboundConfirmed: curr close > prev close
    rebound_confirmed = curr_close > close_prev
    # 3. insideBandWithSupport: close <= mid OR (rsi != null AND rsi <= 30)
    rsi_ok = rsi_12 is not None and rsi_12 <= 30.0
    inside_band = curr_close <= boll_mid or rsi_ok

    if reentered_band and rebound_confirmed and inside_band and qty == 0:
        return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}

    # SELL (3-part OR, qty>0):
    # 1. stopLoss: close < boll_lower
    # 2. takeProfitUpper: close >= boll_upper
    # 3. takeProfitMiddle: close >= boll_mid
    if boll_upper is not None and qty > 0:
        stop_loss = curr_close < boll_lower
        tp_upper = curr_close >= boll_upper
        tp_mid = curr_close >= boll_mid
        if stop_loss or tp_upper or tp_mid:
            return {"action": "SELL", "qty": qty}

    return {"action": "HOLD"}
$code$
    ),
    'v1: strict equivalent of Java BollReversionLongStrategy (boll reenter + rebound + RSI support)',
    'migration'
);

UPDATE strategy_template SET updated_at = now() WHERE template_id = 'tpl_rsi-rebound-python';
