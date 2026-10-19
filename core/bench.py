"""
felix-ai Core -- bench.py
`faze bench` runs the same prompts, with Faze's real system prompt and real tool list, through each online model and
reports what matters for everyday use: did it choose the right tool (or correctly use none), how long did it take, and
did it hit a limit. Only the model's first move is judged; no tool is executed and nothing on the phone changes.

    python3 bench.py                      every provider that has a key (groq, openrouter, gemini)
    python3 bench.py groq gemini          only those
    python3 bench.py --models 2           up to 2 models per provider (default 2)
    python3 bench.py groq --model openai/gpt-oss-120b
    python3 bench.py --list               the models each provider offers, no requests
    python3 bench.py --pause 3            seconds between requests (default 1.5, be kind to free tiers)

Results are also saved to ~/faze_bench.json (FAZE_BENCH_OUT changes the path).
"""

import json
import os
import sys
import time

# (id, prompt, expected tool names or None for "answer directly", argument check)
CASES = [
    ("time", "What time is it right now?", {"get_current_datetime"}, None),
    ("battery", "How much battery have I got left?", {"get_battery_status"}, None),
    ("volume", "Turn the volume up to 6", {"set_volume"}, lambda a: str(a.get("level")) == "6"),
    ("open-app", "Open the calculator", {"open_app"}, lambda a: "calc" in str(a.get("app", "")).lower()),
    ("youtube", "Play some lofi hip hop on YouTube", {"youtube_play"}, lambda a: "lofi" in str(a.get("query", "")).lower()),
    ("weather", "What's the weather like in Abuja?", {"get_weather"}, lambda a: "abuja" in str(a.get("city", "")).lower()),
    ("search-news", "Who won the most recent Ballon d'Or?", {"search_web"}, None),
    ("search-product", "What's the newest iPhone model out right now?", {"search_web"}, None),
    ("wifi", "Am I connected to wifi at the moment?", {"get_wifi_status"}, None),
    ("clipboard", "What's on my clipboard?", {"read_clipboard"}, None),
    ("apps", "Which apps do I have installed on this phone?", {"list_apps"}, None),
    ("url", "Open github.com for me", {"open_url"}, lambda a: "github" in str(a.get("url", "")).lower()),
    ("none-math", "What is 17 times 23?", None, None),
    ("none-joke", "Tell me a one line joke.", None, None),
    ("none-fact", "What's the capital of France?", None, None),
    ("none-explain", "Explain what a mutex is in two sentences.", None, None),
]
OUT_PATH = os.path.expanduser("~/faze_bench.json")


def judge(case, result: dict) -> tuple:
    """(passed, why). `result` is {"content", "tool_calls": [{"name", "arguments"}] or None}."""
    cid, prompt, expected, check = case
    calls = result.get("tool_calls") or []
    if expected is None:
        if calls:
            return False, f"called {calls[0]['name']} for a question that needs no tool"
        return (True, "") if (result.get("content") or "").strip() else (False, "empty answer")
    if not calls:
        return False, "answered without a tool (wanted " + "/".join(sorted(expected)) + ")"
    first = calls[0]
    if first["name"] not in expected:
        return False, f"called {first['name']} (wanted {'/'.join(sorted(expected))})"
    try:
        args = json.loads(first["arguments"]) if isinstance(first["arguments"], str) and first["arguments"] else (first["arguments"] or {})
    except ValueError:
        return False, f"{first['name']} arguments were not valid JSON"
    if check is not None and not check(args if isinstance(args, dict) else {}):
        return False, f"{first['name']} had the wrong arguments: {json.dumps(args)[:80]}"
    return True, ""


def percentile(values: list, p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p * (len(ordered) - 1))))]


def run_model(label: str, ask, cases=CASES, pause: float = 1.5, sleep=time.sleep, clock=time.monotonic) -> dict:
    """ask(prompt) -> result dict, or raises an exception with .kind / .retry_after (RemoteError) ."""
    rows, errors, times, firsts = [], {}, [], []
    limited = False
    for case in cases:
        if limited:
            rows.append({"id": case[0], "passed": False, "why": "skipped: rate limited", "ms": None})
            continue
        started, first = clock(), []
        try:
            result = ask(case[1], lambda: first.append(clock()) if not first else None)
        except Exception as e:
            kind = getattr(e, "kind", type(e).__name__)
            errors[kind] = errors.get(kind, 0) + 1
            rows.append({"id": case[0], "passed": False, "why": f"{kind}: {str(e)[:100]}", "ms": None})
            if kind == "rate_limit":
                wait = float(getattr(e, "retry_after", 0) or 0)
                if 0 < wait <= 20:
                    sleep(wait + 1)
                    try:
                        started, first = clock(), []
                        result = ask(case[1], lambda: first.append(clock()) if not first else None)
                        errors["rate_limit"] -= 1
                        rows.pop()
                    except Exception:
                        limited = True
                        continue
                else:
                    limited = True
                    continue
            else:
                continue
        elapsed = clock() - started
        times.append(elapsed)
        if first:
            firsts.append(first[0] - started)
        passed, why = judge(case, result)
        rows.append({"id": case[0], "passed": passed, "why": why, "ms": int(elapsed * 1000)})
        sleep(pause)
    done = [r for r in rows if r["ms"] is not None]
    passed = sum(1 for r in rows if r["passed"])
    return {"model": label, "passed": passed, "total": len(cases), "answered": len(done), "errors": errors, "rows": rows,
            "avg_s": round(sum(times) / len(times), 2) if times else None, "p95_s": round(percentile(times, 0.95), 2) if times else None,
            "first_token_s": round(sum(firsts) / len(firsts), 2) if firsts else None}


def render(results: list) -> str:
    width = max([len(r["model"]) for r in results] + [5])
    lines = [f"{'model'.ljust(width)}  pass   acc   avg    p95   errors"]
    for r in results:
        acc = f"{100 * r['passed'] // r['total']}%"
        avg = f"{r['avg_s']}s" if r["avg_s"] is not None else "-"
        p95 = f"{r['p95_s']}s" if r["p95_s"] is not None else "-"
        errs = ", ".join(f"{k} x{v}" for k, v in r["errors"].items()) or "-"
        lines.append(f"{r['model'].ljust(width)}  {r['passed']:>2}/{r['total']:<2} {acc:>4}  {avg:>5}  {p95:>5}  {errs}")
    for r in results:
        bad = [x for x in r["rows"] if not x["passed"]]
        if bad:
            lines.append("")
            lines.append(f"{r['model']} missed:")
            lines += [f"  {x['id']}: {x['why']}" for x in bad]
    return "\n".join(lines)


def remote_asker(provider, model, system, schema, chat=None):
    import remote_models
    chat = chat or remote_models.chat

    def ask(prompt, on_first):
        return chat(provider, model, [{"role": "system", "content": system}, {"role": "user", "content": prompt}], schema,
                    on_token=lambda kind, text: on_first(), max_tokens=400)
    return ask


def gemini_asker(system, schema, ask_gemini=None):
    import gemini_client
    ask_gemini = ask_gemini or gemini_client.ask_gemini

    def ask(prompt, on_first):
        r = ask_gemini(system, [{"role": "user", "content": prompt}], tools_schema=schema)
        on_first()
        if r["type"] == "text":
            return {"content": r["content"], "tool_calls": None}
        calls = r.get("calls") or [r]
        return {"content": "", "tool_calls": [{"name": c["name"], "arguments": c.get("args", {})} for c in calls]}
    return ask


def main(argv: list) -> int:
    import config_loader
    import env_loader
    import netfix
    import remote_models
    env_loader.load_env()
    config = config_loader.load_config()
    netfix.install(config)

    providers, per_provider, pause, only_model, listing = [], 2, 1.5, None, False
    it = iter(argv)
    for arg in it:
        if arg == "--models":
            per_provider = int(next(it))
        elif arg == "--model":
            only_model = next(it)
        elif arg == "--pause":
            pause = float(next(it))
        elif arg == "--list":
            listing = True
        elif arg in ("groq", "openrouter", "gemini"):
            providers.append(arg)
        else:
            print(__doc__)
            return 2
    providers = providers or [p for p in ("groq", "openrouter") if remote_models.has_key(p)] + (["gemini"] if os.environ.get("GEMINI_API_KEY") else [])
    if not providers:
        print("no keys found: put GROQ_API_KEY / OPENROUTER_API_KEY / GEMINI_API_KEY in ~/.felix/env")
        return 1

    if listing:
        for p in providers:
            if p == "gemini":
                print(f"gemini: {config['gemini']['model']} (set gemini.model)")
                continue
            try:
                ranked = ranked_models(p)
                print(f"{p}: " + ", ".join(ranked[:15]) + (f" ... ({len(ranked)} total)" if len(ranked) > 15 else ""))
            except remote_models.RemoteError as e:
                print(f"{p}: {e}")
        return 0

    import agent
    from orchestrator import gemini_tool_note
    from prompt_assembler import build_static_system_message
    schema = agent.gemini_schema(config)
    system = build_static_system_message() + gemini_tool_note(schema)
    results = []
    for p in providers:
        if p == "gemini":
            targets = [(config["gemini"]["model"], gemini_asker(system, schema))]
        else:
            try:
                names = [only_model] if only_model else (ranked_models(p)[:per_provider])
            except remote_models.RemoteError as e:
                print(f"{p}: skipped ({e})")
                continue
            targets = [(m, remote_asker(p, m, system, schema)) for m in names]
        for model, ask in targets:
            label = f"{p}/{model}"
            print(f"running {label} ...", flush=True)
            results.append(run_model(label, ask, pause=pause))
    print()
    print(render(results))
    out = os.environ.get("FAZE_BENCH_OUT", OUT_PATH)
    try:
        with open(out, "w") as f:
            json.dump({"at": time.strftime("%Y-%m-%d %H:%M:%S"), "results": results}, f, indent=1)
        print(f"\nsaved to {out}")
    except OSError as e:
        print(f"\ncould not save results: {e}")
    return 0


def ranked_models(provider: str) -> list:
    """The provider's models, best first for tool use: its preferred families, then everything else by context size."""
    import remote_models
    models = remote_models.list_models(provider)
    pool = [m for m in models if m["free"]] if provider == "openrouter" else list(models)
    pool = [m for m in pool if m["tools"] is not False] or pool
    order, seen = [], set()
    for pattern in remote_models.PROVIDERS[provider]["prefer"]:
        for m in sorted((m for m in pool if pattern in m["id"].lower()), key=lambda m: -(m["context"] or 0)):
            if m["id"] not in seen:
                order.append(m["id"])
                seen.add(m["id"])
    order += [m["id"] for m in sorted(pool, key=lambda m: -(m["context"] or 0)) if m["id"] not in seen]
    return order


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
