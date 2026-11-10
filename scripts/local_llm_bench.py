#!/usr/bin/env python3
"""Tries local GGUF models with llama.cpp's llama-server in Termux and reports what matters on a small phone:
how long loading takes, memory (idle and peak), how fast a long prompt is read (the usual reason a model feels slow),
how fast it writes, and a short 3-turn chat to read yourself. If Android kills the server, it says at which step.

Usage:  python3 scripts/local_llm_bench.py MODEL.gguf [MODEL2.gguf ...] [--threads 4] [--ctx 2048]
Needs: llama-server on PATH (or --server PATH). Standard library only. Nothing is sent anywhere; it talks to 127.0.0.1.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = 8089
SHORT = "Say hello in one short sentence."
CHAT_TURNS = [
    "Hi! What is a good way to spend a quiet evening?",
    "I like music and I do not want to go out. Pick one idea and tell me how to start.",
    "Now say that again in two short sentences, like you are talking to me.",
]


def parse_status(text):
    """VmRSS (now) and VmHWM (peak) in MB from the text of /proc/PID/status."""
    out = {}
    for line in text.splitlines():
        for key in ("VmRSS", "VmHWM"):
            if line.startswith(key + ":"):
                out[key] = int(line.split()[1]) / 1024.0
    return out


def read_status(pid):
    try:
        with open("/proc/%d/status" % pid) as f:
            return parse_status(f.read())
    except OSError:
        return {}


def mem_available_mb():
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 1024.0
    except OSError:
        pass
    return 0.0


def long_messages(words=1100):
    """About 1500 tokens: a notes-style system prompt like the app's own, then a question about it."""
    facts = []
    n = 0
    while sum(len(f.split()) for f in facts) < words:
        n += 1
        facts.append("Note %d: the user likes item number %d on days that end in %d, and does not like item %d." % (n, n * 7, n % 7, n * 3))
    system = "You are a helpful phone assistant. What you know about the user:\n" + "\n".join(facts)
    return [{"role": "system", "content": system}, {"role": "user", "content": "Reply with the single word ready."}]


def chat(port, messages, max_tokens, timeout=300):
    body = json.dumps({"messages": messages, "max_tokens": max_tokens, "temperature": 0.3, "stream": False,
                       "chat_template_kwargs": {"enable_thinking": False}}).encode()
    req = urllib.request.Request("http://127.0.0.1:%d/v1/chat/completions" % port, body, {"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.load(r)
    secs = time.time() - t0
    t = data.get("timings") or {}
    usage = data.get("usage") or {}
    return {
        "text": (data["choices"][0]["message"].get("content") or "").strip(),
        "prompt_n": t.get("prompt_n") or usage.get("prompt_tokens") or 0,
        "read_tps": t.get("prompt_per_second") or 0.0,
        "write_tps": t.get("predicted_per_second") or 0.0,
        "seconds": secs,
    }


def wait_ready(port, proc, seconds=120):
    t0 = time.time()
    while time.time() - t0 < seconds:
        if proc.poll() is not None:
            return None
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=2) as r:
                if r.status == 200:
                    return time.time() - t0
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.5)
    return None


def log_tail(path, n=6):
    try:
        with open(path, errors="replace") as f:
            return "".join(f.readlines()[-n:]).strip()
    except OSError:
        return ""


def bench(model, args):
    name = os.path.basename(model)
    res = {"model": name, "size_mb": os.path.getsize(model) / 1048576.0, "free_before_mb": mem_available_mb(), "chat": [], "error": None}
    log = os.path.join(os.path.expanduser("~"), ".faze_bench_%s.log" % name[:40].replace("/", "_"))
    cmd = [args.server, "-m", model, "-c", str(args.ctx), "-t", str(args.threads), "--port", str(args.port), "--host", "127.0.0.1"]
    env = dict(os.environ)
    env.pop("LD_PRELOAD", None)
    with open(log, "w") as lf:
        proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, env=env)
    peak = 0.0
    step = "loading"

    def sample():
        nonlocal peak
        s = read_status(proc.pid)
        peak = max(peak, s.get("VmHWM", 0.0), s.get("VmRSS", 0.0))
        return s.get("VmRSS", 0.0)

    try:
        load = wait_ready(args.port, proc)
        if load is None:
            res["error"] = "the server did not start (%s)" % ("it was killed or crashed" if proc.poll() is not None else "timed out")
            res["log"] = log_tail(log)
            return res
        res["load_s"] = load
        res["idle_mb"] = sample()
        step = "short prompt"
        res["short"] = chat(args.port, [{"role": "user", "content": SHORT}], 40)
        sample()
        step = "long prompt"
        res["long"] = chat(args.port, long_messages(), 8)
        sample()
        history = []
        for i, q in enumerate(CHAT_TURNS):
            step = "chat turn %d" % (i + 1)
            history.append({"role": "user", "content": q})
            r = chat(args.port, history, 120)
            history.append({"role": "assistant", "content": r["text"]})
            res["chat"].append((q, r))
            sample()
    except Exception as e:  # a dead server surfaces as a connection error
        if proc.poll() is not None:
            res["error"] = "the server was killed during: %s (Android ran out of memory, most likely)" % step
        else:
            res["error"] = "failed during %s: %s" % (step, e)
        res["log"] = log_tail(log)
    finally:
        sample()
        res["peak_mb"] = peak
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
    return res


def summarize(r):
    lines = ["=== %s (%.0f MB file, %.0f MB free before) ===" % (r["model"], r["size_mb"], r["free_before_mb"])]
    if r.get("error"):
        lines.append("PROBLEM: " + r["error"])
        if r.get("log"):
            lines.append("last log lines: " + r["log"].replace("\n", " | "))
    if "load_s" in r:
        lines.append("loaded in %.1f s, memory idle %.0f MB, peak %.0f MB" % (r["load_s"], r.get("idle_mb", 0), r.get("peak_mb", 0)))
    if "short" in r:
        s = r["short"]
        lines.append("short prompt: %.1f s to answer, writes %.1f tokens/s" % (s["seconds"], s["write_tps"]))
    if "long" in r:
        l = r["long"]
        lines.append("long prompt (%d tokens): read at %.1f tokens/s, %.1f s before the first word" % (l["prompt_n"], l["read_tps"], l["seconds"]))
    for q, a in r["chat"]:
        lines.append("you: %s" % q)
        lines.append("model (%.1f tokens/s): %s" % (a["write_tps"], a["text"].replace("\n", " ")))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("models", nargs="+")
    ap.add_argument("--server", default=shutil.which("llama-server") or "llama-server")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--ctx", type=int, default=2048)
    ap.add_argument("--port", type=int, default=PORT)
    args = ap.parse_args(argv)
    if not shutil.which(args.server) and not os.path.isfile(args.server):
        print("llama-server was not found. Give its path with --server /path/to/llama-server")
        return 2
    for m in args.models:
        if not os.path.isfile(m):
            print("no such file: " + m)
            return 2
    out = []
    for m in args.models:
        print("running %s ..." % os.path.basename(m), flush=True)
        text = summarize(bench(m, args))
        print(text, flush=True)
        out.append(text)
        time.sleep(3)  # let Android take the memory back before the next model
    path = os.path.join(os.path.expanduser("~"), "faze_bench_results.txt")
    with open(path, "w") as f:
        f.write("\n\n".join(out) + "\n")
    print("\nSaved to " + path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
