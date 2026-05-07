#!/usr/bin/env python3
"""
Python Strategy Runner Bridge
==============================
Reads a JSON request from stdin, executes the user-provided Python strategy
code, calls the specified entrypoint, and prints a JSON result to stdout.

Input JSON structure (stdin):
{
  "code": "def on_bar(ctx): ...",
  "entrypoint": "on_bar",
  "ctx": {
    "params": { ... },
    "indicators": { ... },
    "position": { "qty": 0 },
    "bar": { "symbol": "...", "close": 100.0, "timestamp": "..." }
  }
}

Output JSON structure (stdout) on success:
{
  "success": true,
  "result": { "action": "HOLD" | "BUY" | "SELL", "qty": 100 }
}

Output JSON structure (stdout) on error:
{
  "success": false,
  "error": {
    "type": "SYNTAX_ERROR" | "ENTRYPOINT_NOT_FOUND" | "EXECUTION_ERROR" | "INVALID_RETURN",
    "message": "..."
  }
}
"""

import json
import sys
import traceback


def main():
    request_raw = sys.stdin.read()
    if not request_raw:
        _fail("EXECUTION_ERROR", "No input received on stdin")
        return

    try:
        request = json.loads(request_raw)
    except json.JSONDecodeError as e:
        _fail("EXECUTION_ERROR", f"Invalid JSON input: {e}")
        return

    code = request.get("code", "")
    if not code:
        _fail("EXECUTION_ERROR", "Missing 'code' field")
        return

    entrypoint = request.get("entrypoint", "on_bar")
    ctx = request.get("ctx", {})

    # 1. Compile user code (catches syntax errors)
    try:
        compiled = compile(code, "<strategy_code>", "exec")
    except SyntaxError as e:
        _fail("SYNTAX_ERROR", f"Python syntax error: {e}")
        return
    except Exception as e:
        _fail("SYNTAX_ERROR", f"Compilation error: {e}")
        return

    # 2. Execute in isolated namespace
    namespace = {}
    try:
        exec(compiled, namespace)
    except Exception as e:
        _fail("EXECUTION_ERROR", f"Error during code execution: {e}\n{traceback.format_exc()}")
        return

    # 3. Check entrypoint exists
    if entrypoint not in namespace:
        found = [k for k in namespace.keys() if not k.startswith("__")]
        _fail(
            "ENTRYPOINT_NOT_FOUND",
            f"Entrypoint '{entrypoint}' not found in user code. "
            f"Available definitions: {found}"
        )
        return

    entry_fn = namespace[entrypoint]
    if not callable(entry_fn):
        _fail(
            "ENTRYPOINT_NOT_FOUND",
            f"'{entrypoint}' is not callable (type: {type(entry_fn).__name__})"
        )
        return

    # 4. Call entrypoint(ctx)
    try:
        result = entry_fn(ctx)
    except Exception as e:
        _fail(
            "EXECUTION_ERROR",
            f"Error executing {entrypoint}(ctx): {e}\n{traceback.format_exc()}"
        )
        return

    # 5. Validate result
    if not isinstance(result, dict):
        _fail(
            "INVALID_RETURN",
            f"Entrypoint returned {type(result).__name__}, expected dict. "
            f"Return value: {result}"
        )
        return

    action = result.get("action")
    if action not in ("HOLD", "BUY", "SELL"):
        _fail(
            "INVALID_RETURN",
            f"Invalid action '{action}'. Must be one of: HOLD, BUY, SELL. "
            f"Return value: {result}"
        )
        return

    qty = result.get("qty")
    if action in ("BUY", "SELL") and qty is not None:
        if not isinstance(qty, (int, float)):
            _fail(
                "INVALID_RETURN",
                f"qty must be a number for {action}, got {type(qty).__name__}: {qty}"
            )
            return
        if qty <= 0:
            _fail(
                "INVALID_RETURN",
                f"qty must be positive for {action}, got: {qty}"
            )
            return

    # 6. Success
    _succeed({"action": action, "qty": qty})


def _succeed(result_dict):
    output = {"success": True, "result": result_dict}
    json.dump(output, sys.stdout, ensure_ascii=False)
    sys.stdout.flush()


def _fail(error_type, message):
    output = {"success": False, "error": {"type": error_type, "message": message}}
    json.dump(output, sys.stdout, ensure_ascii=False)
    sys.stdout.flush()


if __name__ == "__main__":
    main()