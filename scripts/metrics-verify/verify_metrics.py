#!/usr/bin/env python3
"""
Verify BacktestMetrics by independently recomputing from trades + klines.

Usage:
  python verify_metrics.py --response logs/backtest-metrics/sample_ok.json --api-base http://host.docker.internal:8181 --market us

Runs inside Docker recommended:
  docker run --rm -v %CD%:/work -w /work python:3.11-slim bash -c "pip install requests; python jzhu-trading/scripts/metrics-verify/verify_metrics.py --response jzhu-trading/logs/backtest-metrics/sample_ok.json --api-base http://host.docker.internal:8181 --market us"
"""
import argparse
import codecs
import json
import math
from pathlib import Path
from datetime import datetime, timedelta

try:
    import requests
except Exception:
    requests = None


def load_json(path):
    raw = Path(path).read_bytes()
    if raw.startswith(codecs.BOM_UTF8):
        text = raw.decode('utf-8-sig')
    elif raw.startswith(codecs.BOM_UTF16_LE) or raw.startswith(codecs.BOM_UTF16_BE):
        text = raw.decode('utf-16')
    else:
        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError:
            text = raw.decode('utf-16')
    return json.loads(text)


def round_or_null(v):
    if v is None:
        return None
    return round(v, 2)


def recompute(klines, trades, leverage=1.0, commission_bps=0.0, fee_rate=0.0):
    """
    对齐 BacktestMetricsCalculator 新版逐 bar mark-to-market 模型。
    leverage/commission_bps/fee_rate 与 Java 侧 RunParameters 对应。
    equity[0] = 1.0 (归一化资本)。
    """
    if isinstance(klines, dict):
        if 'klines' in klines:
            klines = klines['klines']
        elif 'data' in klines:
            klines = klines['data']
    n = len(klines)
    if n == 0:
        return None

    equity = [0.0] * n
    equity[0] = 1.0

    eff_comm = commission_bps / 10000.0 if commission_bps > 0 else fee_rate

    gross_profit = 0.0
    gross_loss = 0.0
    total_hold_bars = 0
    total_hold_days = 0.0

    closed_trades = [t for t in trades if t.get('closed') and t.get('closeIndex', -1) >= 0]

    # Build open/close index maps
    trade_by_open = {}
    trade_by_close = {}
    for t in closed_trades:
        oi = int(t.get('openIndex'))
        ci = int(t.get('closeIndex'))
        trade_by_open[oi] = t
        trade_by_close[ci] = t

        # gross p&l — trade-level (unchanged semantics)
        open_p = t.get('openPrice')
        close_p = t.get('closePrice')
        if not open_p or open_p <= 0:
            continue
        sign = -1 if str(t.get('direction', '')).upper() == 'SHORT' else 1
        raw_ret = sign * (close_p / open_p - 1.0)
        leveraged_ret = raw_ret * leverage
        net_ret = (1.0 + leveraged_ret) * (1.0 - eff_comm) ** 2 - 1.0
        if net_ret > 0:
            gross_profit += net_ret
        if net_ret < 0:
            gross_loss += net_ret

        total_hold_bars += max(0, ci - oi)
        od = t.get('openDate')
        cd = t.get('closeDate')
        if od and cd:
            try:
                odt = datetime.fromisoformat(od)
                cdt = datetime.fromisoformat(cd)
                days = (cdt.date() - odt.date()).days
                total_hold_days += max(0, days)  # include 0-day holds
            except Exception:
                pass

    # State-machine scan: per-bar mark-to-market
    current_trade = None  # tuple: (openIndex, closeIndex, direction, openPrice, closePrice)

    for i in range(0, n):
        prev_equity = equity[0] if i == 0 else equity[i - 1]
        equity[i] = prev_equity  # default flat bar

        # --- open bar ---
        if i in trade_by_open:
            t = trade_by_open[i]
            current_trade = t
            entry = t.get('openPrice')
            bar_close = klines[i]['close']
            sign = -1 if str(t.get('direction', '')).upper() == 'SHORT' else 1
            bar_return = sign * (bar_close / entry - 1.0) * leverage
            fee_open = 1.0 - eff_comm
            equity[i] = prev_equity * (1.0 + bar_return) * fee_open

        # --- mid-holding bar ---
        elif current_trade is not None and i < int(current_trade.get('closeIndex')):
            prev_close = klines[i - 1]['close']
            cur_close = klines[i]['close']
            sign = -1 if str(current_trade.get('direction', '')).upper() == 'SHORT' else 1
            bar_return = sign * (cur_close / prev_close - 1.0) * leverage
            equity[i] = prev_equity * (1.0 + bar_return)

        # --- close bar ---
        elif current_trade is not None and i == int(current_trade.get('closeIndex')):
            t = current_trade
            current_trade = None

            if int(t.get('openIndex')) == i:
                # same-bar open+close (edge case guard)
                entry = t.get('openPrice')
                exit = t.get('closePrice')
                sign = -1 if str(t.get('direction', '')).upper() == 'SHORT' else 1
                raw_ret = sign * (exit / entry - 1.0)
                leveraged_ret = raw_ret * leverage
                fee_factor = (1.0 - eff_comm) ** 2
                equity[i] = prev_equity * (1.0 + leveraged_ret) * fee_factor
            else:
                prev_close = klines[i - 1]['close']
                exit_price = t.get('closePrice')
                sign = -1 if str(t.get('direction', '')).upper() == 'SHORT' else 1
                bar_return = sign * (exit_price / prev_close - 1.0) * leverage
                fee_close = 1.0 - eff_comm
                equity[i] = prev_equity * (1.0 + bar_return) * fee_close

    final_equity = equity[-1]
    total_return_pct = (final_equity - 1.0) * 100.0

    # max drawdown (negative percent)
    running_max = equity[0]
    max_drawdown = 0.0
    for v in equity:
        if v > running_max:
            running_max = v
        else:
            dd = (v / running_max - 1.0) * 100.0
            if dd < max_drawdown:
                max_drawdown = dd

    # daily returns series from equity
    series = []
    for i in range(1, n):
        prev = equity[i-1]
        cur = equity[i]
        if prev <= 0:
            continue
        series.append(cur / prev - 1.0)

    sharpe = None
    annual_return_pct = None
    volatility_pct = None

    if len(series) >= 2:
        mean = sum(series) / len(series)
        var = sum((r - mean) ** 2 for r in series)
        denom = max(1, len(series) - 1)
        std = math.sqrt(var / denom)
        annual_factor = math.sqrt(252.0)
        if std > 0:
            sharpe = mean / std * annual_factor
        volatility_pct = std * annual_factor * 100.0
        # Wall-clock CAGR: use calendar days / 365.0 instead of bar count * 252
        if len(klines) >= 2:
            try:
                first_date = datetime.fromisoformat(klines[0]['date']).date()
                last_date = datetime.fromisoformat(klines[-1]['date']).date()
                calendar_days = (last_date - first_date).days
                if calendar_days > 0:
                    years = calendar_days / 365.0
                    annual_return_pct = (final_equity / equity[0]) ** (1.0 / years) - 1.0
                    annual_return_pct = annual_return_pct * 100.0
                else:
                    # Less than 1 calendar day → total return as fallback
                    annual_return_pct = total_return_pct
            except Exception:
                pass

    closed_count = len(closed_trades)
    wins = 0
    for t in closed_trades:
        open_p = t.get('openPrice')
        close_p = t.get('closePrice')
        if not open_p or open_p <= 0:
            continue
        sign = -1 if str(t.get('direction','')).upper() == 'SHORT' else 1
        r = sign * (close_p / open_p - 1.0)
        if r > 0:
            wins += 1
    win_rate_pct = (wins / closed_count * 100.0) if closed_count > 0 else 0.0

    profit_factor = None
    gross_loss_abs = abs(gross_loss)
    if gross_loss_abs > 0.0:
        profit_factor = gross_profit / gross_loss_abs

    avg_hold_bars = None
    avg_hold_days = None
    if closed_count > 0:
        avg_hold_bars = total_hold_bars / closed_count
        avg_hold_days = total_hold_days / closed_count

    return {
        'totalReturnPct': round_or_null(total_return_pct),
        'maxDrawdownPct': round_or_null(max_drawdown),
        'sharpeRatio': round_or_null(sharpe) if sharpe is not None else None,
        'annualReturnPct': round_or_null(annual_return_pct) if annual_return_pct is not None else None,
        'volatilityPct': round_or_null(volatility_pct) if volatility_pct is not None else None,
        'winRatePct': round_or_null(win_rate_pct),
        'profitFactor': round_or_null(profit_factor) if profit_factor is not None else None,
        'closedTrades': closed_count,
        'averageHoldBars': round_or_null(avg_hold_bars) if avg_hold_bars is not None else None,
        'averageHoldDays': round_or_null(avg_hold_days) if avg_hold_days is not None else None,
        '_equity': equity,
        '_series': series,
    }


def fetch_klines(api_base, symbol, market, start_date, end_date):
    if requests is None:
        raise RuntimeError('requests not available; run with pip install requests inside Docker')
    url = api_base.rstrip('/') + '/api/web/kline'
    params = {
        'symbol': symbol,
        'market': market,
        'period': 'daily',
        'startDate': start_date,
        'endDate': end_date,
    }
    r = requests.get(url, params=params, timeout=30)
    r.raise_for_status()
    data = r.json()
    # expect list of {date, open, high, low, close, volume}
    return data


def compare(expected, actual, thresholds):
    results = []
    for key in ['totalReturnPct', 'maxDrawdownPct', 'sharpeRatio', 'annualReturnPct', 'volatilityPct', 'winRatePct', 'profitFactor', 'closedTrades', 'averageHoldBars', 'averageHoldDays']:
        exp = expected.get(key) if expected else None
        act = actual.get(key) if actual else None
        passed = None
        diff = None
        if exp is None or act is None:
            passed = False
        else:
            if key == 'sharpeRatio':
                th = thresholds['sharpe']
            elif key in ['closedTrades']:
                th = 0.0
            else:
                th = thresholds['percent']
            try:
                diff = abs(float(exp) - float(act))
                passed = diff <= th
            except Exception:
                passed = False
        results.append({'metric': key, 'expected': exp, 'actual': act, 'abs_diff': diff, 'passed': passed})
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--response', required=True)
    p.add_argument('--kline-file')
    p.add_argument('--api-base', default='http://localhost:8181')
    p.add_argument('--market', default='us')
    p.add_argument('--percent-threshold', type=float, default=0.05)
    p.add_argument('--sharpe-threshold', type=float, default=0.05)
    args = p.parse_args()

    resp = load_json(args.response)
    expected_metrics = None
    if 'metrics' in resp and resp['metrics'] is not None:
        expected_metrics = resp['metrics']
    elif 'metrics_recalc' in resp:
        expected_metrics = resp['metrics_recalc']
    else:
        # some test artifacts put expected metrics at top-level keys
        # attempt to collect
        top = {k: resp.get(k) for k in ['totalReturnPct','maxDrawdownPct','sharpeRatio','annualReturnPct','volatilityPct','winRatePct','profitFactor','closedTrades','averageHoldBars','averageHoldDays']}
        if any(v is not None for v in top.values()):
            expected_metrics = top

    trades = resp.get('trades', [])

    if args.kline_file:
        klines = load_json(args.kline_file)
    else:
        # try to determine date range from trades
        dates = []
        for t in trades:
            if t.get('openDate'):
                dates.append(t.get('openDate'))
            if t.get('closeDate'):
                dates.append(t.get('closeDate'))
        if dates:
            min_date = min(dates)
            max_date = max(dates)
            # expand range a bit
            try:
                sdt = datetime.fromisoformat(min_date).date() - timedelta(days=5)
                edt = datetime.fromisoformat(max_date).date() + timedelta(days=5)
                s = sdt.isoformat()
                e = edt.isoformat()
            except Exception:
                s = min_date
                e = max_date
        else:
            # fallback last 2 years
            today = datetime.utcnow().date()
            s = (today - timedelta(days=365*2)).isoformat()
            e = today.isoformat()
        klines = fetch_klines(args.api_base, resp.get('symbol'), args.market, s, e)

    # Extract run parameters from response (if available) — used for leverage/commission/capital matching
    run_params = resp.get('runParameters') or {}
    leverage = float(run_params.get('leverage', 1.0) or 1.0)
    commission_bps = float(run_params.get('commissionBps', 0.0) or 0.0)
    fee_rate = float(run_params.get('feeRate', 0.0) or 0.0)

    computed = recompute(klines, trades, leverage=leverage, commission_bps=commission_bps, fee_rate=fee_rate)

    thresholds = {'percent': args.percent_threshold, 'sharpe': args.sharpe_threshold}
    compare_table = compare(expected_metrics, computed, thresholds)

    for row in compare_table:
        print(f"{row['metric']} expected={row['expected']} actual={row['actual']} diff={row['abs_diff']} {'PASS' if row['passed'] else 'FAIL'}")

    # on failure, print debug excerpts
    failed = [r for r in compare_table if not r['passed']]
    if failed:
        print('\n--- Debug excerpts ---')
        print('equity[0..10]:', computed.get('_equity', [])[:10] if computed else None)
        print('series[0..10]:', computed.get('_series', [])[:10] if computed else None)


if __name__ == '__main__':
    main()
