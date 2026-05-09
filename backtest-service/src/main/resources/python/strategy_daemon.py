#!/usr/bin/env python3
"""
Python Strategy Daemon
======================
Long-running daemon: receive init once (compile & exec user code), then
process per-bar requests via line-delimited JSON over stdin/stdout.

Protocol (each message is exactly one JSON line terminated by \n):

Init (Java → Python):
  {"type":"init","code":"<strategy source>","entrypoint":"on_bar"}

Init response (Python → Java):
  {"type":"init_ok"}
  or
  {"type":"init_error","error":"<traceback>"}

Per-bar request (Java → Python):
  {"type":"bar","ctx":{"params":{...},"indicators":{...},"position":{...},"bar":{...}}}

Per-bar response (Python → Java):
  {"type":"bar_ok","action":"BUY","qty":100}
  or {"type":"bar_ok","action":"HOLD"}
  or {"type":"bar_error","error":"<message>"}

Shutdown (Java → Python):
  {"type":"shutdown"}

Result validation matches python_strategy_runner.py exactly:
- action must be HOLD | BUY | SELL
- qty for BUY/SELL must be int or float, > 0
- result must be a dict
"""

import json
import sys
import traceback


def main():
    # --- Init phase ---
    line = sys.stdin.readline()
    if not line:
        return
    try:
        msg = json.loads(line)
    except json.JSONDecodeError as e:
        _write({"type": "init_error", "error": f"Invalid init JSON: {e}"})
        return

    if msg.get("type") != "init":
        _write({"type": "init_error", "error": f"Expected init, got: {msg.get('type')}"})
        return

    code = msg.get("code", "")
    if not code:
        _write({"type": "init_error", "error": "Missing 'code' field"})
        return

    entrypoint = msg.get("entrypoint", "on_bar")

    # Compile and exec user code
    try:
        compiled = compile(code, "<strategy_code>", "exec")
    except SyntaxError as e:
        _write({"type": "init_error", "error": f"Python syntax error: {e}"})
        return
    except Exception as e:
        _write({"type": "init_error", "error": f"Compilation error: {e}"})
        return

    namespace = {}
    try:
        exec(compiled, namespace)
    except Exception:
        _write({"type": "init_error", "error": f"Error during code execution:\n{traceback.format_exc()}"})
        return

    if entrypoint not in namespace:
        found = [k for k in namespace if not k.startswith("__")]
        _write({
            "type": "init_error",
            "error": f"Entrypoint '{entrypoint}' not found. Available: {found}"
        })
        return

    entry_fn = namespace[entrypoint]
    if not callable(entry_fn):
        _write({
            "type": "init_error",
            "error": f"'{entrypoint}' is not callable (type: {type(entry_fn).__name__})"
        })
        return

    _write({"type": "init_ok"})

    # --- Bar loop ---
    for line in sys.stdin:
        try:
            msg = json.loads(line)
        except json.JSONDecodeError as e:
            _write({"type": "bar_error", "error": f"Invalid bar JSON: {e}"})
            continue

        msg_type = msg.get("type")
        if msg_type == "shutdown":
            break
        if msg_type != "bar":
            _write({"type": "bar_error", "error": f"Unknown message type: {msg_type}"})
            continue

        ctx = msg.get("ctx", {})

        try:
            result = entry_fn(ctx)
        except Exception:
            _write({"type": "bar_error", "error": f"Error in {entrypoint}(ctx):\n{traceback.format_exc()}"})
            continue

        # Validate result (1:1 match with python_strategy_runner.py)
        if not isinstance(result, dict):
            _write({
                "type": "bar_error",
                "error": f"Entrypoint returned {type(result).__name__}, expected dict. Value: {result}"
            })
            continue

        action = result.get("action")
        if action not in ("HOLD", "BUY", "SELL"):
            _write({
                "type": "bar_error",
                "error": f"Invalid action '{action}'. Must be HOLD, BUY, or SELL. Value: {result}"
            })
            continue

        qty = result.get("qty")
        if action in ("BUY", "SELL") and qty is not None:
            if not isinstance(qty, (int, float)):
                _write({
                    "type": "bar_error",
                    "error": f"qty must be a number for {action}, got {type(qty).__name__}: {qty}"
                })
                continue
            if qty <= 0:
                _write({
                    "type": "bar_error",
                    "error": f"qty must be positive for {action}, got: {qty}"
                })
                continue

        _write({"type": "bar_ok", "action": action, "qty": qty})


def _write(obj):
    json.dump(obj, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
