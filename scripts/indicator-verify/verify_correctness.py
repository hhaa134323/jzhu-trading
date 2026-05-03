from __future__ import annotations

import argparse
import json
import math
from collections import OrderedDict
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from statistics import fmean
from typing import Any


MARKETS = [
    {"market": "us", "symbol": "TSLA"},
    {"market": "hk", "symbol": "00700"},
    {"market": "cn", "symbol": "600519"},
]
PERIODS = ["daily", "weekly", "monthly"]
MA_WINDOWS = [5, 10, 20, 30, 60]
BOLL_WINDOW = 20
BOLL_K = 2.0
MACD_FAST = 12
MACD_SLOW = 26
MACD_SIGNAL = 9
RSI_WINDOWS = [6, 12, 24]


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def dump_json(path: Path, value: Any) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)


def round_half_up(value: float) -> float:
    return float(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def bucket_date(iso_date: str, period: str) -> str:
    current = date.fromisoformat(iso_date)
    if period == "weekly":
        return (current - timedelta(days=current.weekday())).isoformat()
    if period == "monthly":
        return current.replace(day=1).isoformat()
    return iso_date


def aggregate_klines(klines: list[dict[str, Any]], period: str) -> list[dict[str, Any]]:
    grouped: OrderedDict[str, list[dict[str, Any]]] = OrderedDict()
    for item in sorted(klines, key=lambda row: row["date"]):
        grouped.setdefault(bucket_date(item["date"], period), []).append(item)

    aggregated: list[dict[str, Any]] = []
    for bucket, rows in grouped.items():
        rows = sorted(rows, key=lambda row: row["date"])
        first = rows[0]
        last = rows[-1]
        aggregated.append(
            {
                "date": bucket,
                "open": first["open"],
                "high": max(row["high"] for row in rows),
                "low": min(row["low"] for row in rows),
                "close": last["close"],
                "volume": sum(int(row["volume"]) for row in rows),
            }
        )

    return aggregated


def compare_numeric_series(expected: list[float | None], actual: list[float | None]) -> dict[str, Any]:
    if len(expected) != len(actual):
        raise ValueError(f"series length mismatch: expected {len(expected)}, actual {len(actual)}")

    valid_diffs: list[Decimal] = []
    mismatched_nulls = 0
    worst_samples: list[dict[str, Any]] = []

    for idx, (exp, act) in enumerate(zip(expected, actual)):
        if exp is None or act is None:
            if exp is not None or act is not None:
                mismatched_nulls += 1
                worst_samples.append({"index": idx, "expected": exp, "actual": act, "diff": None})
            continue

        exp_dec = Decimal(str(exp))
        act_dec = Decimal(str(act))
        diff = abs(exp_dec - act_dec)
        valid_diffs.append(diff)
        if diff != 0:
            worst_samples.append(
                {
                    "index": idx,
                    "expected": float(exp_dec),
                    "actual": float(act_dec),
                    "diff": float(diff),
                }
            )

    max_abs_error = float(max(valid_diffs)) if valid_diffs else 0.0
    mean_abs_error = float(fmean(float(value) for value in valid_diffs)) if valid_diffs else 0.0
    return {
        "count": len(valid_diffs),
        "null_mismatch_count": mismatched_nulls,
        "max_abs_error": max_abs_error,
        "mean_abs_error": mean_abs_error,
        "pass": mismatched_nulls == 0 and max_abs_error <= 1e-6,
        "worst_samples": worst_samples[:5],
    }


def rolling_ma(closes: list[float], window: int) -> list[float | None]:
    result: list[float | None] = []
    rolling_sum = 0.0
    for idx, close in enumerate(closes):
        rolling_sum += close
        if idx >= window:
            rolling_sum -= closes[idx - window]
        if idx < window - 1:
            result.append(None)
        else:
            result.append(round_half_up(rolling_sum / window))
    return result


def boll(closes: list[float]) -> tuple[list[float | None], list[float | None], list[float | None]]:
    upper: list[float | None] = []
    middle: list[float | None] = []
    lower: list[float | None] = []
    for idx in range(len(closes)):
        if idx < BOLL_WINDOW - 1:
            upper.append(None)
            middle.append(None)
            lower.append(None)
            continue
        window = closes[idx - BOLL_WINDOW + 1 : idx + 1]
        mean = sum(window) / BOLL_WINDOW
        variance = sum((value - mean) ** 2 for value in window) / BOLL_WINDOW
        std = math.sqrt(variance)
        middle.append(round_half_up(mean))
        upper.append(round_half_up(mean + BOLL_K * std))
        lower.append(round_half_up(mean - BOLL_K * std))
    return upper, middle, lower


def ema(values: list[float], period: int) -> list[float]:
    alpha = 2.0 / (period + 1.0)
    result: list[float] = []
    current = values[0]
    for idx, value in enumerate(values):
        if idx == 0:
            current = value
        else:
            current = value * alpha + current * (1.0 - alpha)
        result.append(current)
    return result


def macd(closes: list[float]) -> tuple[list[float], list[float], list[float]]:
    ema_fast = ema(closes, MACD_FAST)
    ema_slow = ema(closes, MACD_SLOW)
    dif_raw = [fast - slow for fast, slow in zip(ema_fast, ema_slow)]
    dea_raw: list[float] = []
    alpha = 2.0 / (MACD_SIGNAL + 1.0)
    for idx, dif in enumerate(dif_raw):
        if idx == 0:
            dea_raw.append(dif)
        else:
            dea_raw.append(dif * alpha + dea_raw[-1] * (1.0 - alpha))
    hist_raw = [(dif - dea) * 2.0 for dif, dea in zip(dif_raw, dea_raw)]
    return (
        [round_half_up(value) for value in dif_raw],
        [round_half_up(value) for value in dea_raw],
        [round_half_up(value) for value in hist_raw],
    )


def rsi(closes: list[float], period: int) -> list[float | None]:
    result: list[float | None] = [None] * len(closes)
    if len(closes) <= period:
        return result

    gain_sum = 0.0
    loss_sum = 0.0
    for idx in range(1, period + 1):
        diff = closes[idx] - closes[idx - 1]
        if diff > 0:
            gain_sum += diff
        else:
            loss_sum += -diff

    avg_gain = gain_sum / period
    avg_loss = loss_sum / period

    def to_rsi(gain: float, loss: float) -> float:
        if loss == 0.0:
            return 100.0
        rs = gain / loss
        return 100.0 - (100.0 / (1.0 + rs))

    result[period] = round_half_up(to_rsi(avg_gain, avg_loss))
    for idx in range(period + 1, len(closes)):
        diff = closes[idx] - closes[idx - 1]
        gain = max(diff, 0.0)
        loss = max(-diff, 0.0)
        avg_gain = ((avg_gain * (period - 1)) + gain) / period
        avg_loss = ((avg_loss * (period - 1)) + loss) / period
        result[idx] = round_half_up(to_rsi(avg_gain, avg_loss))
    return result


def series_from_response(block: dict[str, Any], key: str) -> list[Any]:
    value = block.get(key)
    if value is None:
        return []
    return list(value)


def indicator_stats(service_response: dict[str, Any], klines: list[dict[str, Any]]) -> dict[str, Any]:
    closes = [float(row["close"]) for row in klines]
    expected = {
        "ma": {window: rolling_ma(closes, window) for window in MA_WINDOWS},
        "boll": dict(zip(["upper", "middle", "lower"], boll(closes))),
        "macd": dict(zip(["dif", "dea", "hist"], macd(closes))),
        "rsi": {window: rsi(closes, window) for window in RSI_WINDOWS},
    }

    actual = {
        "ma": {
            5: series_from_response(service_response["ma"], "ma5List"),
            10: series_from_response(service_response["ma"], "ma10List"),
            20: series_from_response(service_response["ma"], "ma20List"),
            30: series_from_response(service_response["ma"], "ma30List"),
            60: series_from_response(service_response["ma"], "ma60List"),
        },
        "boll": {
            "upper": series_from_response(service_response["boll"], "upperList"),
            "middle": series_from_response(service_response["boll"], "middleList"),
            "lower": series_from_response(service_response["boll"], "lowerList"),
        },
        "macd": {
            "dif": series_from_response(service_response["macd"], "difList"),
            "dea": series_from_response(service_response["macd"], "deaList"),
            "hist": series_from_response(service_response["macd"], "macdList"),
        },
        "rsi": {
            6: series_from_response(service_response["rsi"], "rsi6List"),
            12: series_from_response(service_response["rsi"], "rsi12List"),
            24: series_from_response(service_response["rsi"], "rsi24List"),
        },
    }

    result = {"ma": {}, "boll": {}, "macd": {}, "rsi": {}}
    for window in MA_WINDOWS:
        result["ma"][window] = compare_numeric_series(expected["ma"][window], actual["ma"][window])
    for key in ["upper", "middle", "lower"]:
        result["boll"][key] = compare_numeric_series(expected["boll"][key], actual["boll"][key])
    for key in ["dif", "dea", "hist"]:
        result["macd"][key] = compare_numeric_series(expected["macd"][key], actual["macd"][key])
    for window in RSI_WINDOWS:
        result["rsi"][window] = compare_numeric_series(expected["rsi"][window], actual["rsi"][window])
    return result


def dataset_stats(service_klines: list[dict[str, Any]], expected_klines: list[dict[str, Any]]) -> dict[str, Any]:
    if len(service_klines) != len(expected_klines):
        return {
            "pass": False,
            "count": min(len(service_klines), len(expected_klines)),
            "mismatch_count": abs(len(service_klines) - len(expected_klines)),
            "worst_samples": [
                {
                    "expected_count": len(expected_klines),
                    "actual_count": len(service_klines),
                    "diff": None,
                }
            ],
        }

    fields = ["date", "open", "high", "low", "close", "volume"]
    mismatches: list[dict[str, Any]] = []
    for idx, (expected_row, actual_row) in enumerate(zip(expected_klines, service_klines)):
        row_mismatch = False
        sample = {"index": idx, "date": expected_row["date"], "fields": {}}
        for field in fields:
            if expected_row[field] != actual_row[field]:
                row_mismatch = True
                sample["fields"][field] = {"expected": expected_row[field], "actual": actual_row[field]}
        if row_mismatch:
            mismatches.append(sample)

    return {
        "pass": len(mismatches) == 0,
        "count": len(expected_klines),
        "mismatch_count": len(mismatches),
        "worst_samples": mismatches[:5],
    }


def format_bool(value: bool) -> str:
    return "pass" if value else "fail"


def render_table(headers: list[str], rows: list[list[Any]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(["---"] * len(headers)) + " |"]
    for row in rows:
        lines.append("| " + " | ".join(str(item) for item in row) + " |")
    return "\n".join(lines)


def code_refs() -> dict[str, str]:
    return {
        "market_aggregation": "market-data-service/src/main/java/ai/jzhu/trading/marketdata/application/usecase/GetKlineUseCase.java - aggregateKlines / resolveBucketDate / toResponse",
        "indicator_dispatch": "indicator-service/src/main/java/ai/jzhu/trading/indicator/application/usecase/CalculateIndicatorsUseCase.java - execute",
        "ma": "indicator-service/src/main/java/ai/jzhu/trading/indicator/domain/calculator/MaCalculator.java - calculate / movingAverage",
        "boll": "indicator-service/src/main/java/ai/jzhu/trading/indicator/domain/calculator/BollCalculator.java - calculate",
        "macd": "indicator-service/src/main/java/ai/jzhu/trading/indicator/domain/calculator/MacdCalculator.java - calculate",
        "rsi": "indicator-service/src/main/java/ai/jzhu/trading/indicator/domain/calculator/RsiCalculator.java - calculateRsi / toRsi",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--start-date", required=True)
    parser.add_argument("--end-date", required=True)
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    report: dict[str, Any] = {
        "generatedAt": None,
        "dateRange": {"start": args.start_date, "end": args.end_date},
        "parameters": {
            "maWindows": MA_WINDOWS,
            "boll": {"window": BOLL_WINDOW, "k": BOLL_K},
            "macd": {"fast": MACD_FAST, "slow": MACD_SLOW, "signal": MACD_SIGNAL, "histMultiplier": 2},
            "rsiWindows": RSI_WINDOWS,
            "rounding": "half-up to 2 decimals",
            "weeklyBucket": "previous or same Monday",
            "monthlyBucket": "first day of month",
        },
        "codeRefs": code_refs(),
        "markets": [],
    }

    summary_lines: list[str] = []
    summary_lines.append("# Indicator Correctness Report")
    summary_lines.append("")
    summary_lines.append(f"Generated from `logs/indicator-verify` for {args.start_date} to {args.end_date}.")
    summary_lines.append("")
    summary_lines.append("## Parameter Contract")
    summary_lines.append("")
    summary_lines.append("| item | value | code location |")
    summary_lines.append("| --- | --- | --- |")
    summary_lines.append(f"| MA windows | 5 / 10 / 20 / 30 / 60 | {report['codeRefs']['ma']} |")
    summary_lines.append(f"| BOLL | window={BOLL_WINDOW}, K={BOLL_K}, population std (ddof=0) | {report['codeRefs']['boll']} |")
    summary_lines.append(f"| MACD | fast={MACD_FAST}, slow={MACD_SLOW}, signal={MACD_SIGNAL}, hist = 2*(DIF-DEA) | {report['codeRefs']['macd']} |")
    summary_lines.append(f"| RSI | windows 6 / 12 / 24, Wilder smoothing | {report['codeRefs']['rsi']} |")
    summary_lines.append(f"| Volume | compare kline volume directly; indicator endpoint does not return a volume series | {report['codeRefs']['market_aggregation']} |")
    summary_lines.append("")

    def add_indicator_family(title: str, family: dict[str, Any], names: list[tuple[str, Any]]) -> None:
        summary_lines.append(f"### {title}")
        summary_lines.append("")
        rows = []
        for label, key in names:
            stats = family[key]
            rows.append([
                label,
                stats["count"],
                stats["null_mismatch_count"],
                f"{stats['max_abs_error']:.6g}",
                f"{stats['mean_abs_error']:.6g}",
                format_bool(stats["pass"]),
                json.dumps(stats["worst_samples"][:1], ensure_ascii=False),
            ])
        summary_lines.append(render_table(["series", "valid points", "null mismatches", "max abs error", "mean abs error", "status", "sample"], rows))
        summary_lines.append("")

    for market_entry in MARKETS:
        market = market_entry["market"]
        symbol = market_entry["symbol"]
        market_block: dict[str, Any] = {"market": market, "symbol": symbol, "periods": {}}
        summary_lines.append(f"## {market.upper()} / {symbol}")
        summary_lines.append("")

        daily_base = load_json(input_dir / f"{market}_{symbol}_daily_kline.json")
        market_block["dailyBaseCount"] = len(daily_base)

        period_rows = []
        for period in PERIODS:
            service_klines = load_json(input_dir / f"{market}_{symbol}_{period}_kline.json")
            service_indicator = load_json(input_dir / f"{market}_{symbol}_{period}_indicator.json")
            expected_klines = daily_base if period == "daily" else aggregate_klines(daily_base, period)

            dataset_result = dataset_stats(service_klines, expected_klines)
            indicator_result = indicator_stats(service_indicator, service_klines)

            market_block["periods"][period] = {
                "dataset": dataset_result,
                "indicators": indicator_result,
                "serviceCount": len(service_klines),
                "expectedCount": len(expected_klines),
            }

            period_rows.append([
                period,
                len(service_klines),
                dataset_result["pass"],
                dataset_result["mismatch_count"],
            ])

        summary_lines.append(render_table(["period", "rows", "dataset pass", "dataset mismatches"], period_rows))
        summary_lines.append("")

        summary_lines.append("### Volume / Kline Alignment")
        summary_lines.append("")
        summary_lines.append(render_table(
            ["period", "rows", "volume compare", "mismatch count", "sample"],
            [
                [
                    period,
                    market_block["periods"][period]["serviceCount"],
                    format_bool(market_block["periods"][period]["dataset"]["pass"]),
                    market_block["periods"][period]["dataset"]["mismatch_count"],
                    json.dumps(market_block["periods"][period]["dataset"]["worst_samples"][:1], ensure_ascii=False),
                ]
                for period in PERIODS
            ],
        ))
        summary_lines.append("")

        for period in PERIODS:
            summary_lines.append(f"### {period.capitalize()} indicators")
            summary_lines.append("")
            indicators = market_block["periods"][period]["indicators"]
            add_indicator_family("MA", indicators["ma"], [("MA5", 5), ("MA10", 10), ("MA20", 20), ("MA30", 30), ("MA60", 60)])
            add_indicator_family("BOLL", indicators["boll"], [("upper", "upper"), ("middle", "middle"), ("lower", "lower")])
            add_indicator_family("MACD", indicators["macd"], [("DIF", "dif"), ("DEA", "dea"), ("Hist", "hist")])
            add_indicator_family("RSI", indicators["rsi"], [("RSI6", 6), ("RSI12", 12), ("RSI24", 24)])

        report["markets"].append(market_block)

    report["generatedAt"] = __import__("datetime").datetime.now().isoformat()
    dump_json(output_dir / "correctness-results.json", report)

    summary_lines.append("## Conclusion")
    summary_lines.append("")
    any_fail = False
    for market_block in report["markets"]:
        for payload in market_block["periods"].values():
            if not payload["dataset"]["pass"]:
                any_fail = True
            for family in [payload["indicators"]["ma"], payload["indicators"]["boll"], payload["indicators"]["macd"], payload["indicators"]["rsi"]]:
                for stats in family.values():
                    if not stats["pass"]:
                        any_fail = True
    summary_lines.append("pass" if not any_fail else "fail")
    summary_lines.append("")
    summary_lines.append("If a failure appears, the most likely code locations are listed in the parameter contract above.")

    (output_dir / "correctness-report.md").write_text("\n".join(summary_lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()