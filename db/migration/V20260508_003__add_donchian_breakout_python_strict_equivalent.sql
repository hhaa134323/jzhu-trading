-- Migration: Donchian Breakout Python v1 — strict equivalent of Java BullishLongStrategy
-- See: BullishLongStrategy.java (getId()="donchianBreakoutLong")

INSERT INTO strategy_template (template_id, name, description, owner_id)
VALUES (
    'tpl_donchian-breakout-python',
    'Donchian Breakout (Python)',
    'Donchian通道突破策略的 Python 版本（等价 BullishLongStrategy）',
    'migration'
);

INSERT INTO strategy_template_version (template_id, version_no, source_kind, definition_json, change_note, created_by)
VALUES (
    'tpl_donchian-breakout-python',
    1,
    'PYTHON_CODE',
    jsonb_build_object(
        'engineType', 'PYTHON',
        'entrypoint', 'on_bar',
        'baseStrategyId', 'donchianBreakoutLong',
        'parameters', jsonb_build_object('breakoutLookbackBars', 20, 'pullbackMaPeriod', 10),
        'code', $code$
def on_bar(ctx):
    # Donchian Breakout v1 — strict equivalent of Java BullishLongStrategy
    # Params: breakout_lookback_bars (default 20) / pullback_ma_period (default 10)
    #   mapped from StrategyParameters.breakoutLookbackBars / pullbackMaPeriod via RunBacktestUseCase
    # Indicators: rolling_high, rolling_low, close_prev, ma_20 (injected by adapter)

    lookback = ctx["params"].get("breakout_lookback_bars", 20)
    exit_lookback = ctx["params"].get("pullback_ma_period", 10)

    rolling_high = ctx["indicators"].get("rolling_high")
    rolling_low = ctx["indicators"].get("rolling_low")
    close_prev = ctx["indicators"].get("close_prev")
    ma_20 = ctx["indicators"].get("ma_20")

    curr_close = ctx["bar"]["close"]
    qty = ctx["position"].get("qty", 0)

    # BUY: prev close <= rolling_high AND curr close > rolling_high (Donchian breakout)
    if rolling_high is not None and close_prev is not None:
        if close_prev <= rolling_high and curr_close > rolling_high and qty == 0:
            return {"action": "BUY", "qty": ctx["params"].get("qty", 100)}

    # SELL: close < rolling_low (breakdown) OR close < ma_20 (MA filter exit)
    if rolling_low is not None and close_prev is not None:
        exit_breakdown = curr_close < rolling_low
        exit_ma = ma_20 is not None and curr_close < ma_20
        if (exit_breakdown or exit_ma) and qty > 0:
            return {"action": "SELL", "qty": qty}

    return {"action": "HOLD"}
$code$
    ),
    'v1: strict equivalent of Java BullishLongStrategy (Donchian breakout/breakdown + MA20 filter)',
    'migration'
);

UPDATE strategy_template SET updated_at = now() WHERE template_id = 'tpl_donchian-breakout-python';
