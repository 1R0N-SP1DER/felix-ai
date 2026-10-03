# GEMINI.md — Faze / felix-ai project context

You are working inside Felix's repo for **Faze** (repo name `felix-ai`): a private, offline-first, voice-first personal AI assistant that runs on a rooted Android phone (primary) and a Linux PC (second peer). Read this whole file before touching anything. When this file and a request conflict, stop and ask.

Felix is an independent developer, technically fluent (Linux, Android shell, root, Termux, Python, Git, Gradle). Skip basic explanations. Be concise: his usage limits are a real constraint.

---

## 1. Philosophy

1. **Offline-first.** Local models and deterministic code handle everything they can. Cloud (Gemini) is an escalation path for search, delegation to a stronger model, and hard reasoning. It is never the default path, and Faze must stay usable with Gemini fully off.
2. **One identity, two bodies.** Phone and PC are peers sharing one assistant identity ("Faze"), one memory (the phone's SQLite DB is the source of truth), and one personality. The PC is the more powerful body and runs its own local model so it works if the phone is unreachable.
3. **LLM output is untrusted input.** Models emit structured tool calls. Code validates them against a schema, checks the permission tier, executes, and returns the real result to the model. **Never execute raw model-generated shell.**
4. **Truth over fluency.** A small model confidently inventing a battery percentage is the worst failure mode seen so far. Anything state-dependent (battery, time, files, clipboard, search results) must come from a real tool result, never from model memory.
5. **Human approval gates are non-negotiable** for anything that changes Faze's own capabilities (see section 9).

---

## 2. Environment

### Phone (primary, further along)
- Redmi 10C, rooted (Evolution X, Magisk/KernelSU), **<4GB RAM, chronically near-full**. RAM and context size are hard constraints. Do not add anything that raises the baseline footprint without saying so.
- Runs in **Termux** (bash). Heredocs work here. Android 12+ phantom process killer is relevant: Felix keeps it enabled (cap raised, not disabled). Do not tell him to disable it.
- Code at `~/felix-ai/core`. DB at `~/felix-ai/felix_ai_memory.db`.
- Local model: **Qwen3.5-0.8B-Q4_K_M** (516 MB) via **llama.cpp's `llama-server`**, built **from source** in `~/llama.cpp` and served at `127.0.0.1:8080` with OpenAI-compatible `/v1/chat/completions`, `-c 4096` currently.
  - **Never `pkg install llama-cpp`.** It silently hijacks shared libraries and crashes the from-source `llama-server` (`GGML_ASSERT(params.speculative.draft.n_gpu_layers < 0)`).
  - Thinking must be off by default: every request to llama-server sets `"chat_template_kwargs": {"enable_thinking": false}`.
- Older/alternate local models: LiquidAI LFM2.5-230M (fast, ~153MB, hallucinates badly on facts, only suitable for chitchat), Cactus Needle (45M tool-calling model, `--serve` on port 8765, trusted only at confidence ≥ 0.5). Needle/LFM layers are legacy from earlier design.
- Gemini via **plain REST + `requests`** (`gemini_client.py`). The official google-genai SDK cannot install on Termux (Rust deps, no aarch64-android wheels). Do not introduce it, or any dependency needing Rust, native wheels, or heavy builds. Check `pip` wheel availability for Termux before suggesting any new package. Prefer the stdlib.
- Gemini endpoint: `v1beta/models/gemini-3.1-flash-lite:generateContent`, header `x-goog-api-key`. Free tier ~15 RPM / 1,000 RPD. Respect it.

### PC
- Linux Mint, **fish shell** (not bash), Caelestia desktop (Wayland). Heredocs do **not** work in fish. When giving PC commands for multi-line files, use `nano` or `printf '%s' '...' > file`. Use `wl-copy`/`wl-paste`, never `xclip`.
- Ollama via Snap on port **11435** (moved), serving Qwen2.5-1.5B-Instruct-Q4_K_M as `qwen-test` with an explicit ChatML template.
- `ollama_shim.py` impersonates Ollama on port 11434 so Caelestia's Nexus sidebar can talk to Faze (model name `faze:latest`). Single shared `PCConversation`.
- PC code is in `pc_core/` (`orchestrator_pc.py`, `pc_tool_schema.py`, `pc_permission_policy.py`, `pc_tool_executor.py`). PC side is stable and **not the current priority**. Do not touch it unless asked.

---

## 3. Repo map (phone, `~/felix-ai/core`)

| File | Role |
|---|---|
| `orchestrator.py` | Main loop, routing, history, local+Gemini tool loops. **Accumulated layered patches; slated for a clean rewrite (section 8).** |
| `direct_tool_router.py` | Regex router for known-safe phrasings (vibrate, battery, datetime, clipboard, volume). Still in the path; may be removed in the rewrite. |
| `tool_schema.py` | `TOOL_REGISTRY`: tool names, params, validation. Single source of truth for tools. |
| `gemini_tools.py` | Hand-written `TOOL_DESCRIPTIONS`; builds the `functionDeclarations` schema (`GEMINI_TOOLS_SCHEMA`), reused as `LOCAL_TOOLS_SCHEMA`. |
| `tool_executor.py` | Real implementations (Termux API / root commands). Raises `ToolExecutionError`. |
| `permission_policy.py` | safe / confirm / block tiers, `device_overrides` (can only tighten, never loosen). |
| `gemini_client.py` | REST client: `ask_gemini`, `ask_gemini_stream`, function-call parsing. |
| `prompt_assembler.py` | Builds the system prompt (persona + memory block + tool instructions). |
| `bootstrap_import.py` | One-time import of the 62 bootstrap memory facts. |
| `phone_server.py` | Minimal HTTP server (port 8766, 0.0.0.0) wrapping the phone Conversation for PC↔phone calls. Written, **untested**. |

Current tools (10): battery status, datetime, clipboard read/write, volume, vibrate, and three escape-hatch tools: `delegate_to_gemini` (ask Gemini directly when unsure), `search_web` (Gemini with Google Search grounding, a **separate request** because Gemini cannot mix search grounding with function-calling tools; use the `google_search` tool field), `enable_thinking_mode` (last resort when Gemini is unreachable).

**Tool descriptions matter more than they seem.** Small models only call tools reliably when descriptions say *when* to call, with examples ("Call this whenever the user asks about battery, charge level, or power status. Takes no arguments."). Never replace a hand-written description with an auto-generated one.

---

## 4. Current state and known bugs (as of 2026-10-03)

**Working**
- Local Qwen3.5 tool loop with proper `role: "tool"` messages and `tool_call_id` now returns real results (confirmed: real battery value). This fixed the earlier bug where tool results were fed back as fake `role: "user"` messages and the model hallucinated.
- Gemini function calling, regex fast path, token streaming for the local model, trimming (system + last 20 messages), memory framing ("Felix: <fact>" + "you are NOT Felix").

**Known problems (priority order)**
1. **llama-server dies mid-session** (phantom process killer; cap needs raising to 256 via `device_config`). Any code that depends on llama-server must handle connection refused gracefully.
2. **`delegate_to_gemini` / `search_web` fail** through the Gemini path with a generic "having trouble running those tools" wrapper that hides the real exception. Also circular: Gemini should not be offered `delegate_to_gemini`. Local model also paraphrases the request arg (turned "camero" into "camera"), so the user's raw message should be passed through.
3. **First message takes ~1 minute**: prompt evaluation of a ~7000-char system prompt (62 memories, persona, 10 tool schemas) on a weak CPU. Later turns are fast thanks to llama-server prefix caching.
4. **Uncaught exception in the local fallback** inside `except GeminiError` in `send()`: if Gemini fails and the local call fails, Faze crashes instead of degrading to a plain "couldn't get an answer" reply.
5. Gemini occasionally breaks character ("trained by Google"). Low priority.
6. Offline-first priority flip and a Gemini on/off flag were requested earlier. Verify their status in the code before assuming either exists.

---

## 5. Memory architecture

SQLite `felix_ai_memory.db`:
- `conversation_log`: kept forever. Never delete or truncate.
- `memories`: facts about Felix, soft-delete via `active` flag (never hard-delete). Has a `category` column (`me`, `conversation-style`, `devices`, …).
- `rules`: highest authority; always wins over memories and persona.
- `style_profile`: single evolving row.

**Problem:** everything loads into the prompt every turn (~7000 chars).

**Target design (selective recall), to be built first:**
- **Always-on core (<~800 chars):** assistant name, `rules`, the `style_profile` row, identity framing ("you are Faze, NOT Felix").
- **Per-message retrieval, deterministic and with no model call:** SQLite **FTS5** over `memories`, top ~5 matches for the current user message, with `category` used as a filter/boost (e.g. "my phone" → `devices`). Fall back to a small default set if there are no hits.
- **Cache-friendly placement:** the static prefix (persona, rules, tool instructions) must be byte-identical turn to turn so llama-server's KV cache survives. Put retrieved facts and volatile info (time) **after** the static prefix.
- Do **not** implement recall as a model-callable tool: the 0.8B model will not reliably choose to call it.
- Check FTS5 availability in Termux's SQLite first (`sqlite3 :memory: "create virtual table t using fts5(a);"`). If it is missing, use a LIKE/keyword-overlap scorer instead and tell Felix.
- Schema changes must be additive and migration-safe, with a backup of the DB first.

---

## 6. Routing and the target orchestrator

Current legacy order: regex → Needle (conf ≥ 0.5) → Gemini → local model (offline fallback). That is online-first and Felix wants the reverse.

**Target (clean rewrite of `orchestrator.py`):**
- **One message-history format**: OpenAI-style (`system`/`user`/`assistant` with `tool_calls`/`tool` with `tool_call_id`). Local and Gemini share one tool loop through thin adapters (Gemini's `functionCall`/`functionResponse` mapped at the edge). No parallel loops with different history shapes.
- **Offline-first ordering:** deterministic fast path (only if kept) → local Qwen (tools, reasoning, intent) → Gemini only on genuine escalation (model asks via `delegate_to_gemini`/`search_web`, or fails/uncertain) → plain failure message. A config flag fully disables the Gemini layer.
- **Qwen is the brain; a faster model may handle only trivial chitchat.** Idea under consideration: pre-boot llama-server in the start script with a warmup request using the exact production prefix; optionally a tiny LFM instance for instant "hi"-level replies while Qwen warms. Do not add a second resident model without discussing RAM first.
- **Every external call wrapped.** No path may crash on connection refused, timeouts, malformed JSON, or Gemini quota errors. Degrade through the chain and always return something truthful.
- Max tool rounds per turn (currently 3). Keep a hard cap.
- Keep token streaming for the local path (print as tokens arrive, return the full string for history/logging, no double printing).

---

## 7. Action logging

Add a consistent trace so **every tool call across all layers** prints the same format and is optionally persisted:

```
[layer -> tool] {args}  => result (ms)
```

Layers: `regex`, `qwen`, `gemini`, `thinking`. Include: validation failures, permission-tier decisions (safe/confirm/block), executor errors with real exception text, fallbacks taken and why (e.g. `local failed: ConnectionRefused -> gemini`). Also log to a rotating file (e.g. `~/felix-ai/logs/actions.log`, size-capped) that **never contains the API key or full memory contents**. This log is how we debug; never swallow an exception without logging it.

---

## 8. `/model` commands

In the chat loop, implement:
- `/model gemini|qwen|lfm` forces that model for the rest of the session.
- `/model <name> <n>` forces it for the next *n* messages, then reverts to auto.
- `/model auto` restores normal routing; `/model` alone prints the current mode.
- Forced mode still goes through the same validation/permission/executor path. Forcing a model never bypasses security. If the forced model is unreachable, say so plainly instead of silently falling back.

---

## 9. AI-generated tools with human approval (designed, not yet built)

When no existing tool covers a request, Gemini may propose new tools. **Two steps, two human checkpoints:**
1. **Proposal (cheap, no code):** Gemini describes in plain English what tool it would build, its params, and its permission tier. Felix approves or denies. A denied proposal costs nothing further.
2. **Code (only after approval):** Gemini generates real code matching `tool_schema.py` / `tool_executor.py` / `permission_policy.py` conventions. Felix reviews the **actual code** before it is registered.

Rules:
- Generated tools land in a **staging area** (e.g. `core/tools_staged/`), not the live registry, until approved.
- Default tier for new tools is **confirm**, never safe, until Felix promotes it.
- Staged code is validated (syntax check, schema check) and never auto-imported/executed before approval. No `eval`/`exec` of model output, no raw shell strings.
- Extended pattern: Gemini solves a novel multi-step task live with existing tools; afterwards it proposes a tool replicating that sequence so the task runs offline next time (same two-step approval).

---

## 10. What you (Gemini CLI) may modify

- Files in `~/felix-ai/core/` for the current task: `orchestrator.py`, `prompt_assembler.py`, `gemini_client.py`, `gemini_tools.py`, `tool_schema.py`, `tool_executor.py`, `permission_policy.py`, and new modules you create for the task (recall, action log, model commands).
- Tests and scripts you add under a `tests/` or `scripts/` directory.
- Make **small, scoped changes**. One concern per change. Show a diff and explain briefly before big edits.
- Always create a **git commit (or at minimum a `.bak` copy) before editing a file**, so any change is revertible.

## 11. What you must NEVER touch or expose

- **Secrets:** the Gemini API key, any token. Never print it, log it, commit it, paste it into a prompt, or hardcode it. Read from env var or an ignored config file. If you find a key in source, flag it and propose moving it. Do not "fix" it silently.
- **`felix_ai_memory.db`** and `memory_snapshot.txt`: personal data. Never commit, upload, or send contents to any cloud API. Never run destructive SQL (`DROP`, `DELETE`, `TRUNCATE`, unscoped `UPDATE`) on it. Back it up (`cp`) before any schema migration. Memory contents must not be echoed into logs. Never send raw DB rows to Gemini unless Felix explicitly asks.
- `conversation_log`: append-only. `memories`: soft-delete only. `rules`: do not modify.
- `.gitignore` must cover the DB, snapshots, logs, `.env`/config with keys. Verify before any push; if the repo is public, treat anything committed as leaked.
- **`pc_core/` and the PC Ollama setup**, unless asked.
- `permission_policy.py` semantics: never loosen a tier, add a `safe` tier tool without approval, or let `device_overrides` loosen anything.
- `~/llama.cpp` (do not rebuild or modify), Termux system packages (no `pkg install llama-cpp`), root/Magisk settings, and device config (`device_config`, `settings`) unless Felix explicitly requests that exact command.
- The persona/style prompt text: don't rewrite or "improve" it without asking. It's tuned for a 0.8B model.

---

## 12. Testing expectations

- **Never claim something works unless you ran it** and saw the real output. "Syntax checked" is not "works": say which you did.
- After any change: `python -m py_compile` on touched files, then a smoke test through the real path where feasible.
- Provide **exact commands and the expected output stated up front**, then wait for Felix's real output before assuming success.
- Acceptance checks for the main features:
  - Tool loop: "what's my battery" must show a real tool call and the real value; no number may appear without a preceding tool result.
  - Failure modes: with llama-server stopped, with Gemini unreachable (airplane mode), and with both down, Faze must degrade and never crash with a traceback.
  - Memory recall: log which memories were retrieved per message; check prompt size dropped substantially (target static prompt well under current ~7000 chars) and that first-message latency improved.
  - Prefix stability: verify the static prefix is identical across turns.
  - `/model`: forced mode works for N messages, then returns to auto; invalid input handled.
- Prefer small, repeatable test scripts (mock llama-server and Gemini responses) over manual-only testing, since the device is slow and RAM-bound.
- Measure rather than guess for performance: report time to first token and prompt size, not impressions.

---

## 13. Behaviour when uncertain

- **Say you don't know.** Do not invent file contents, function names, library APIs, flags, or Termux behaviours. Open and read the actual file or run `grep` first.
- If a request is ambiguous in a way that changes the design, ask **one** precise question, then proceed. For obvious steps, don't ask for confirmation.
- When a fix is a guess, label it a hypothesis and give the cheapest command that would confirm or refute it.
- Report real exception text. Never replace an error with a vague summary.
- Don't paste large files back at Felix. Use targeted `grep`/`sed -n` ranges and show only the relevant lines.

### Stop instead of blindly editing when
- A change would touch secrets, the DB, `permission_policy.py` semantics, or anything in section 11.
- The same fix has failed twice. Stop, show the evidence, and rethink rather than stacking patches (that's how `orchestrator.py` got into its current state).
- You'd need a new dependency (check Termux wheel availability first, and ask).
- The change raises RAM use meaningfully, adds a resident process, or increases the system prompt size.
- A command is destructive or irreversible (`rm -rf`, `git reset --hard`, force-push, DB writes) or needs root.
- Tests can't be run and the change is large. Present the plan and wait.

---

## 14. Coding style

- **Complete, working files. No placeholders, no `TODO: implement`, no `...`.** If you change a file substantially, it must run as-is.
- Python, stdlib-first, Termux-compatible. Type hints where useful, short docstrings stating purpose and failure behaviour.
- Modular: one responsibility per module; keep `orchestrator.py` thin after the rewrite. Config-driven (assistant name, wake phrase, ports, model names, thresholds) and never hardcoded.
- Error handling and logging included by default. Narrow `except` clauses with real logging; no bare `except: pass`.
- Don't add abstraction for its own sake, and don't leave dead code from old eras (LFM2.5 workarounds, Needle) once replaced. Delete it in the same change, noting what you removed.
- No emoji or chatty prose inside code or commit messages. Commit messages: short, imperative.
- Shell commands: Termux/bash on the phone, **fish** on the PC. When in doubt, state which shell the command is for.
- Replies to Felix: concise, command-first, no recaps of what he already knows.

---

## 15. How the finished Faze should work

- Voice-first on the phone: wake phrase ("Faze, you there?"), offline STT/TTS (Piper or Sherpa-ONNX), an Android app UI. Later phases, not current work.
- Starts fast: llama-server pre-booted with a warm KV cache, small static prefix, selective memory recall, so the first reply isn't a minute away.
- Simple requests (battery, time, clipboard, volume, apps) resolve locally and instantly from real tool results. Conversation sounds like a person texting (casual, short, matches his energy), and goes serious and clear when he is.
- Hard questions escalate to Gemini transparently (search with grounding, delegation, deep reasoning), but Faze keeps working offline when the network or API is gone, and says honestly when it can't do something.
- One memory, one identity across phone and PC; peers can call each other (phone ↔ PC over tethering/USB or Bluetooth, since they're not on the same network) and act on each other's behalf.
- Faze can grow its own tools: Gemini proposes, Felix approves twice, and repeated tasks become instant offline tools.
- A readable action log makes every tool call, fallback, and failure traceable.
- The full thing is private by design: personal data stays on Felix's devices, and cloud calls carry only what a given request needs.

---

## 16. Immediate task order

1. Make llama-server survive (raise phantom process cap to 256; handle connection-refused cleanly in code).
2. Selective memory recall + shrink static prompt (section 5).
3. Fix `search_web` / `delegate_to_gemini` (surface real exceptions, drop `delegate_to_gemini` from Gemini's own tool list, pass the raw user message).
4. Action log (section 7).
5. `/model` commands (section 8).
6. Clean `orchestrator.py` rewrite (section 6), including the try/except around the local fallback and the offline-first flip with a Gemini on/off flag.
7. Two-step approved tool-authoring (section 9).
8. Pending later: auto-detect phone RAM to set llama-server context size instead of fixed 4096; PC↔phone link test; PC `web_search` tool; vision (SmolVLM) when on unmetered data.

## 18. Usage efficiency and precision

Felix's free-tier quota is limited. Every request and token counts.
- Read only what you need: use grep and `sed -n 'a,bp'` for line ranges, not whole-file reads, unless you're rewriting that file. Never re-read a file you just wrote.
- Plan once, then edit. No exploratory edit-and-retry loops. Batch related changes to a file into a single write.
- Keep replies and tool narration short: no recaps, no restating the task, no long explanations. Show a diff or command plus expected output, nothing more.
- Don't run broad commands (`find /`, `cat` on large files, `git log -p`, recursive greps without `--include`). Scope everything to `~/felix-ai/core`.
- Don't call Gemini or any cloud API from tests; use mocks.
- Verify before editing: confirm real function names, signatures and column names by reading them. Never guess an API, schema or flag.
- Verify after editing: `python -m py_compile` on every touched file, then run the specific test. Only report success from real output, and quote the line that proves it.
- If a fix fails once, stop, read the actual error, and make one targeted correction. If it fails twice, stop and ask Felix. Don't loop.
- One task at a time, finished and committed before starting the next.
