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


def recompute(klines, trades):
    if isinstance(klines, dict):
        if 'klines' in klines:
            klines = klines['klines']
        elif 'data' in klines:
            klines = klines['data']
    # klines: list of dict with at least 'close' and 'date'
    n = len(klines)
    if n == 0:
        return None

    equity = [0.0] * n
    equity[0] = 1.0

    returns_at_index = {}
    gross_profit = 0.0
    gross_loss = 0.0
    total_hold_bars = 0
    total_hold_days = 0.0

    closed_trades = [t for t in trades if t.get('closed') and t.get('closeIndex', -1) >= 0]

    for t in closed_trades:
        open_p = t.get('openPrice')
        close_p = t.get('closePrice')
        if not open_p or open_p <= 0:
            continue
        sign = -1 if str(t.get('direction','')).upper() == 'SHORT' else 1
        ret = sign * (close_p / open_p - 1.0)
        idx = int(t.get('closeIndex'))
        returns_at_index.setdefault(idx, []).append(ret)
        if ret > 0:
            gross_profit += ret
        if ret < 0:
            gross_loss += ret
        oidx = int(t.get('openIndex', 0))
        total_hold_bars += max(0, idx - oidx)
        od = t.get('openDate')
        cd = t.get('closeDate')
        if od and cd:
            try:
                odt = datetime.fromisoformat(od)
                cdt = datetime.fromisoformat(cd)
                days = (cdt.date() - odt.date()).days
                if days > 0:
                    total_hold_days += days
            except Exception:
                pass

    for i in range(1, n):
        equity[i] = equity[i-1]
        lst = returns_at_index.get(i)
        if lst:
            mult = 1.0
            for r in lst:
                mult *= (1.0 + r)
            equity[i] = equity[i-1] * mult

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
        days = float(n)
        if days > 0:
            annual_return_pct = (final_equity / equity[0]) ** (252.0 / days) - 1.0
            annual_return_pct = annual_return_pct * 100.0

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

    computed = recompute(klines, trades)

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
