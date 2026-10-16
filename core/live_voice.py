"""
felix-ai Core -- live_voice.py
The dedicated live voice mode: a spoken conversation with Gemini Live, run on the server. `faze start --live` starts it;
the overlay shows it. The classic voice pipeline (faze start --voice) is untouched.

The wake listener's microphone stream goes up to Gemini as 16 kHz PCM; Gemini's 24 kHz audio comes back and is played
as it arrives; both sides' transcripts, tool calls (run here, with the usual permission and phone-control gates) and
Google Search all happen inside the one session. By default the microphone is muted while Gemini speaks (the phone's own
speaker would otherwise be heard as you interrupting); live.barge_in turns interruption on, best with headphones.

LiveUnavailable means nothing was said yet and the caller can fall back to the classic pipeline.
"""

import os
import queue
import threading
import time

import action_log
import live_client
import net_status
from live_client import LiveConnection, LiveError, PcmPlayer

TAIL_S = 0.35          # microphone stays muted this long after Gemini stops
MAX_TOOL_TEXT = 4000
NEVER_IN_LIVE = {"listen", "create_tool"}  # listen would fight for the microphone; creating tools needs the app's approval card
VOICE_NOTE = (
    "\nYou are speaking aloud in a live voice conversation. Answer in one to three short, natural sentences; no markdown, "
    "no lists, no emojis, nothing that is awkward to say out loud. Use tools for anything about this phone, and Google "
    "Search for current facts if you have it. If a tool fails, say so in a few words instead of guessing.\n")


class LiveUnavailable(Exception):
    pass


def declarations(convo, with_search: bool) -> list:
    import agent
    from gemini_client import _tools_to_function_declarations
    skip = set(NEVER_IN_LIVE) | ({"search_web"} if with_search else set())
    schema = [t for t in agent.gemini_schema(convo.config) if t["function"]["name"] not in skip]
    return _tools_to_function_declarations(schema)


def run(session, gen: int, cancel: threading.Event, connect=None, player=None, clock=time.monotonic) -> None:
    from voice_session import DONE, ERROR, LISTENING, SPEAKING, THINKING
    server, convo = session.server, session.server.convo
    cfg = server.config
    live = cfg["live"]
    key = os.environ.get("GEMINI_API_KEY", "")
    if not key:
        raise LiveUnavailable("no GEMINI_API_KEY")
    if connect is None and not live_client.available():
        raise LiveUnavailable("the websockets package is not installed (pip install websockets)")
    if not net_status.online(cfg):
        raise LiveUnavailable("offline")
    listener = server.wake
    if listener is None or not listener._live():
        raise LiveUnavailable("it needs the wake listener's microphone stream (turn on wake.enabled)")
    player = player or PcmPlayer(live.get("player", ""))
    if not player.usable():
        raise LiveUnavailable("no audio player: install pulseaudio (pacat) or ffmpeg (ffplay), or set live.player")
    if not server.chat_lock.acquire(blocking=False):
        session._set(gen, state=ERROR, error="Faze is still answering the last message")
        return

    started = clock()
    marks = {}
    conn = LiveConnection(key, str(live.get("model") or "gemini-3.8-live"), connect=connect)
    stop_mic = threading.Event()
    barge_in = bool(live.get("barge_in", False))
    idle_limit = float(live.get("idle_seconds", 15))
    max_seconds = float(live.get("max_seconds", 600))
    held = False
    try:
        with_search = bool(live.get("search", True))
        try:
            conn.open(convo.system_static + VOICE_NOTE, declarations(convo, with_search), with_search,
                      voice=str(cfg["tts"]["voice"]).capitalize())
        except LiveError as e:
            raise LiveUnavailable(str(e))
        marks["connect"] = clock()
        if not conn.search:
            with_search = False
        listener.hold(True)
        held = True

        state = {"speaking": False, "turn_done": False, "gate_until": 0.0, "activity": clock(), "turns": 0, "ending": False}
        text = {"user": "", "reply": ""}
        timing = {}

        def mic_open() -> bool:
            if barge_in:
                return True
            return not state["speaking"] and not player.busy() and clock() >= state["gate_until"]

        def mic():
            try:
                for frame in listener.frames(stop_mic):
                    if stop_mic.is_set() or cancel.is_set():
                        return
                    if mic_open():
                        conn.send_audio(frame)
            except Exception as e:  # a closed socket ends the mic quietly; the receiver reports it
                action_log.log_action("live", "mic", {}, f"stopped: {e}", 0, echo=False)

        threading.Thread(target=mic, daemon=True).start()
        end_note = ""

        def listening():
            state.update(speaking=False, turn_done=False, gate_until=clock() + TAIL_S, activity=clock())
            session._set(gen, state=LISTENING)

        def new_turn_if_needed():
            if state["turn_done"] or text["reply"]:
                text["user"], text["reply"] = "", ""
                state["turn_done"] = False
                session._set(gen, transcript="", reply="")

        def finish_timing():
            t = {"connect_ms": int((marks["connect"] - started) * 1000), "turns": state["turns"]}
            if "audio" in timing and "user" in timing:
                t["first_audio_ms"] = max(0, int((timing["audio"] - timing["user"]) * 1000))
            return t

        def send_typed():
            """Text typed into the overlay goes to Gemini as a turn of its own; it interrupts anything being said."""
            while True:
                try:
                    typed = session._typed.get_nowait()
                except queue.Empty:
                    return
                if state["speaking"] or player.busy():
                    player.stop()
                state.update(speaking=False, turn_done=False, activity=clock(), gate_until=clock() + TAIL_S)
                text["user"], text["reply"] = typed, ""
                timing["user"] = clock()
                session._set(gen, transcript=typed, reply="", state=THINKING)
                conn.send_text(typed)

        while not cancel.is_set():
            send_typed()
            now = clock()
            if now - started > max_seconds:
                end_note = "Live session time limit reached"
                break
            events = conn.poll(0.2)
            if not events:
                if state["speaking"] and state["turn_done"] and not player.busy():
                    listening()
                if state["ending"] and not state["speaking"]:
                    break
                if (not state["speaking"] and not state["turn_done"] and now - state["activity"] > idle_limit
                        and not text["user"] and not text["reply"]):
                    end_note = end_note or "Conversation ended (nobody spoke)"
                    break
                if not state["speaking"] and now - state["activity"] > idle_limit * 2:
                    end_note = end_note or "Conversation ended (idle)"
                    break
                continue
            state["activity"] = now
            ended = False
            for ev in events:
                kind = ev[0]
                if kind == "in_text":
                    new_turn_if_needed()
                    text["user"] += ev[1]
                    timing["user"] = now
                    session._set(gen, transcript=text["user"].strip())
                elif kind == "out_text":
                    text["reply"] += ev[1]
                    session._set(gen, reply=text["reply"].strip(), state=SPEAKING if state["speaking"] else THINKING)
                elif kind == "audio":
                    if not state["speaking"]:
                        state["speaking"] = True
                        timing["audio"] = now
                        marks.setdefault("first_audio", now)
                        session._set(gen, state=SPEAKING)
                    player.write(ev[1])
                elif kind == "tool_call":
                    session._set(gen, state=THINKING)
                    responses = []
                    for call in ev[1]:
                        ok, out = convo._execute_tool(call["name"], call["args"], "live")
                        responses.append({"id": call["id"], "name": call["name"], "ok": ok, "text": str(out)[:MAX_TOOL_TEXT]})
                    conn.send_tool_responses(responses)
                elif kind == "interrupted":
                    player.stop()
                    listening()
                elif kind == "turn_complete":
                    state["turns"] += 1
                    state["turn_done"] = True
                    player.finish()
                    if not state["speaking"]:
                        listening()
                elif kind == "go_away":
                    end_note = "Live session is ending soon"
                    state["ending"] = True
                elif kind == "closed":
                    if state["turns"] == 0 and not text["user"]:
                        session._set(gen, state=ERROR, error="the Live session closed: " + str(ev[1])[:160])
                        return
                    end_note = "Live session closed" + (f" ({str(ev[1])[:80]})" if ev[1] and "1000" not in str(ev[1]) else "")
                    ended = True
                    break
            if ended or (state["ending"] and state["turn_done"] and not player.busy()):
                break
        if not cancel.is_set():
            t = finish_timing()
            action_log.log_action("live", "timings", t, end_note or "ended", 0, echo=False)
            session._set(gen, state=DONE, timings=t, note=end_note or "Conversation ended")
    except LiveError as e:
        session._set(gen, state=ERROR, error=str(e)[:200])
    finally:
        stop_mic.set()
        player.stop()
        conn.close()
        if held:
            listener.hold(False)
        server.chat_lock.release()
