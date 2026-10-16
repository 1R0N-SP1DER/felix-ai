"""
Actions (open links/apps, YouTube), search that survives Gemini's quota, online wake-word recognition,
background service script, /doctor.

    PYTHONPATH=. python3 -m unittest tests.test_patch12 -v
"""
import base64
import os
import subprocess
import sys
import tempfile
import time
import unittest
from collections import deque
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(CORE)
sys.path.insert(0, CORE)

import actions  # noqa: E402
import doctor  # noqa: E402
import gemini_client  # noqa: E402
import gemini_tools  # noqa: E402
import stt  # noqa: E402
import tool_executor  # noqa: E402
import wake  # noqa: E402
import web_search  # noqa: E402
from config_loader import SETTINGS, load_config  # noqa: E402
from gemini_client import GeminiError  # noqa: E402
from tests.test_patch8 import HAVE_JSDOM, run_smoke  # noqa: E402
from tool_executor import ToolExecutionError  # noqa: E402


def proc(out="", code=0, err=""):
    return mock.Mock(stdout=out, stderr=err, returncode=code)


class ActionCase(unittest.TestCase):
    def setUp(self):
        actions._APPS.update(at=0.0, packages=None, launchers=None)
        actions.SETTINGS["root"] = "auto"
        self.addCleanup(lambda: actions.SETTINGS.update(root="auto"))


class TestOpenUrl(ActionCase):
    def test_only_real_web_links(self):
        for bad in ("", "javascript:alert(1)", "file:///etc/passwd", "intent://x", "https://", "https://a.com/\nx", "https://a.com/" + "x" * 2100, None):
            with self.assertRaises(actions.ActionError, msg=str(bad)[:20]):
                actions.open_url(bad)

    def test_android_launch_command(self):
        with mock.patch.object(actions, "_is_android", return_value=True), mock.patch("subprocess.run", return_value=proc("Starting: Intent")) as run:
            self.assertEqual(actions.open_url("https://youtu.be/abc"), "Opened youtu.be")
        self.assertEqual(run.call_args[0][0], ["am", "start", "-a", "android.intent.action.VIEW", "-d", "https://youtu.be/abc"])

    def test_blocked_launch_retries_through_root(self):
        blocked = proc("Error: Activity not started, unable to resolve Intent", 0)
        with mock.patch.object(actions, "_is_android", return_value=True), mock.patch("shutil.which", return_value="/su"), \
                mock.patch("subprocess.run", side_effect=[blocked, proc("Starting")]) as run:
            self.assertEqual(actions.open_url("https://a.com/x?y=1&z=2"), "Opened a.com (via root)")
        second = run.call_args_list[1][0][0]
        self.assertEqual(second[:2], ["su", "-c"])
        self.assertIn("'https://a.com/x?y=1&z=2'", second[2])  # quoted as one argument

    def test_root_modes(self):
        blocked = proc("Error: boom", 1)
        with mock.patch.object(actions, "_is_android", return_value=True):
            actions.SETTINGS["root"] = "never"
            with mock.patch("subprocess.run", return_value=blocked) as run, self.assertRaises(actions.ActionError) as cm:
                actions.open_url("https://a.com")
            self.assertEqual(run.call_count, 1)
            self.assertIn("actions.root", str(cm.exception))
            actions.SETTINGS["root"] = "always"
            with mock.patch("shutil.which", return_value="/su"), mock.patch("subprocess.run", return_value=proc("Starting")) as run:
                actions.open_url("https://a.com")
            self.assertEqual(run.call_args[0][0][:2], ["su", "-c"])
            self.assertEqual(run.call_count, 1)
            actions.SETTINGS["root"] = "auto"
            with mock.patch("shutil.which", return_value=None), mock.patch("subprocess.run", return_value=blocked), self.assertRaises(actions.ActionError):
                actions.open_url("https://a.com")

    def test_desktop_fallback(self):
        with mock.patch.object(actions, "_is_android", return_value=False), mock.patch("shutil.which", return_value="/usr/bin/xdg-open"), \
                mock.patch("subprocess.Popen") as popen:
            actions.open_url("https://a.com")
        self.assertEqual(popen.call_args[0][0], ["/usr/bin/xdg-open", "https://a.com"])
        with mock.patch.object(actions, "_is_android", return_value=False), mock.patch("shutil.which", return_value=None), self.assertRaises(actions.ActionError):
            actions.open_url("https://a.com")


class TestOpenApp(ActionCase):
    def test_known_and_fuzzy_packages(self):
        with mock.patch.object(actions, "_is_android", return_value=True), mock.patch("subprocess.run", return_value=proc("Starting")) as run:
            self.assertEqual(actions.open_app("YouTube app"), "Opened YouTube app (com.google.android.youtube)")
        self.assertEqual(run.call_args[0][0][-2:], ["-p", "com.google.android.youtube"])
        listing = proc("package:com.foo.bar\npackage:com.acme.supercalc\npackage:com.acme.supercalc.extra")
        with mock.patch.object(actions, "_is_android", return_value=True), mock.patch("subprocess.run", side_effect=[listing, proc(""), proc("Starting")]) as run:
            actions.open_app("super calc")
        self.assertEqual(run.call_args[0][0][-1], "com.acme.supercalc")
        with mock.patch.object(actions, "_is_android", return_value=True), mock.patch("subprocess.run", return_value=proc("package:com.foo.bar")), \
                self.assertRaises(actions.ActionError) as cm:
            actions.open_app("nonexistent thing")
        self.assertIn("no installed app matches", str(cm.exception))
        with self.assertRaises(actions.ActionError):
            actions.open_app("")


PAGE = ('..."videoRenderer":{"videoId":"dQw4w9WgXcQ","thumbnail":{},"title":{"runs":[{"text":"The \\"Best\\" Phone of 2026"}],"accessibility":{}}'
        ',"x":1},{"videoRenderer":{"videoId":"bbbbbbbbbbb","title":{"runs":[{"text":"Second"}]}}}')


class TestYouTube(ActionCase):
    def test_parse_first_video(self):
        self.assertEqual(actions._first_video(PAGE), ("dQw4w9WgXcQ", 'The "Best" Phone of 2026'))
        self.assertEqual(actions._first_video('"videoId":"aaaaaaaaaaa"')[0], "aaaaaaaaaaa")
        self.assertIsNone(actions._first_video("nothing here"))

    def test_latest_tries_the_channel_then_sorted_search(self):
        urls = []

        def fetch(url):
            urls.append(url)
            if "/videos" in url:
                raise actions.ActionError("404")
            return PAGE
        with mock.patch.object(actions, "_fetch", side_effect=fetch):
            self.assertEqual(actions.youtube_find("mrwhosetheboss", latest=True)[0], "dQw4w9WgXcQ")
        self.assertEqual(urls[0], "https://www.youtube.com/@mrwhosetheboss/videos")
        self.assertIn("results?search_query=mrwhosetheboss&sp=CAI%253D", urls[1])
        urls.clear()
        with mock.patch.object(actions, "_fetch", side_effect=fetch):
            actions.youtube_find("best phone 2026", latest=False)
        self.assertEqual(len(urls), 1)
        self.assertNotIn("sp=", urls[0])

    def test_play_opens_the_video_or_falls_back_to_search(self):
        with mock.patch.object(actions, "_fetch", return_value=PAGE), mock.patch.object(actions, "open_url", return_value="ok") as opened:
            msg = actions.youtube_play("mrwhosetheboss latest video")
        opened.assert_called_once_with("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        self.assertIn('Playing The "Best" Phone of 2026', msg)
        with mock.patch.object(actions, "_fetch", side_effect=actions.ActionError("down")), mock.patch.object(actions, "open_url", return_value="ok") as opened:
            msg = actions.youtube_play("some channel")
        self.assertIn("results?search_query=some+channel", opened.call_args[0][0])
        self.assertIn("couldn't pick", msg)
        with self.assertRaises(actions.ActionError):
            actions.youtube_play("   ")

    def test_latest_words_are_filler(self):
        seen = []
        with mock.patch.object(actions, "youtube_find", side_effect=lambda q, latest: seen.append((q, latest)) or ("aaaaaaaaaaa", "t")), \
                mock.patch.object(actions, "open_url", return_value="ok"):
            actions.youtube_play("the newest mkbhd video")
            actions.youtube_play("lofi beats")
        self.assertEqual(seen, [("mkbhd", True), ("lofi beats", False)])


HTML_PAGE = '''
<div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=abc">Example &amp; <b>Title</b></a>
<a class="result__snippet" href="x">First <b>snippet</b> text</a></div>
<div class="result"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad=1">Sponsored</a><a class="result__snippet">ad</a></div>
<div class="result"><a rel="nofollow" class="result__a" href="https://second.org/page">Second</a><a class="result__snippet">Second snippet</a></div>'''
LITE_PAGE = '''<table><tr><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Flite.example%2Fp" class='result-link'>Lite Title</a></td></tr>
<tr><td class='result-snippet'>Lite snippet</td></tr></table>'''


class TestWebSearch(unittest.TestCase):
    def test_parse_html_layout(self):
        r = web_search.parse_results(HTML_PAGE)
        self.assertEqual([x["url"] for x in r], ["https://example.com/a?x=1", "https://second.org/page"])  # ad skipped, redirect unwrapped
        self.assertEqual((r[0]["title"], r[0]["snippet"]), ("Example & Title", "First snippet text"))

    def test_parse_lite_layout_and_limit(self):
        r = web_search.parse_results(LITE_PAGE)
        self.assertEqual((r[0]["title"], r[0]["url"], r[0]["snippet"]), ("Lite Title", "https://lite.example/p", "Lite snippet"))
        self.assertEqual(len(web_search.parse_results(HTML_PAGE, limit=1)), 1)
        self.assertEqual(web_search.parse_results("<html>blocked</html>"), [])

    def test_endpoint_fallback_and_errors(self):
        pages = [mock.Mock(status_code=202, text=""), mock.Mock(status_code=200, text=LITE_PAGE)]
        calls = []
        r = web_search.ddg_search("q", post=lambda url, **kw: calls.append(url) or pages.pop(0))
        self.assertEqual(r[0]["title"], "Lite Title")
        self.assertEqual(calls, list(web_search.ENDPOINTS))
        with self.assertRaises(web_search.SearchError):
            web_search.ddg_search("q", post=lambda *a, **k: mock.Mock(status_code=200, text="<html></html>"))
        with self.assertRaises(web_search.SearchError):
            web_search.ddg_search("  ")
        text = web_search.format_results("q", web_search.parse_results(HTML_PAGE))
        self.assertIn("1. Example & Title: First snippet text\n   https://example.com/a?x=1", text)


class TestSearchTool(unittest.TestCase):
    def setUp(self):
        tool_executor.SEARCH.update(engine="auto", blocked_until=0.0)
        self.addCleanup(lambda: tool_executor.SEARCH.update(engine="auto", blocked_until=0.0))
        self.results = [{"title": "T", "url": "https://t.example", "snippet": "S"}]

    def test_gemini_answer_is_used_when_it_works(self):
        with mock.patch("tool_executor.ask_gemini", return_value={"type": "text", "content": "Paris"}), mock.patch("web_search.ddg_search") as ddg:
            self.assertEqual(tool_executor.search_web({"query": "capital of france"}), "Paris")
        ddg.assert_not_called()

    def test_quota_error_falls_back_and_pauses_gemini(self):
        with mock.patch("tool_executor.ask_gemini", side_effect=GeminiError("429 quota exceeded for gemini-3.1-flash-lite")) as gem, \
                mock.patch("web_search.ddg_search", return_value=self.results):
            first = tool_executor.search_web({"query": "news"})
            second = tool_executor.search_web({"query": "more news"})
        self.assertIn("1. T: S", first)
        self.assertIn("https://t.example", second)
        self.assertEqual(gem.call_count, 1)  # the breaker kept it from asking again
        self.assertGreater(tool_executor.SEARCH["blocked_until"], time.monotonic())

    def test_other_gemini_errors_fall_back_without_pausing(self):
        with mock.patch("tool_executor.ask_gemini", side_effect=GeminiError("network down")), mock.patch("web_search.ddg_search", return_value=self.results):
            self.assertIn("1. T: S", tool_executor.search_web({"query": "x"}))
        self.assertEqual(tool_executor.SEARCH["blocked_until"], 0.0)

    def test_engine_settings(self):
        tool_executor.SEARCH["engine"] = "web"
        with mock.patch("tool_executor.ask_gemini") as gem, mock.patch("web_search.ddg_search", return_value=self.results):
            tool_executor.search_web({"query": "x"})
        gem.assert_not_called()
        tool_executor.SEARCH["engine"] = "gemini"
        with mock.patch("tool_executor.ask_gemini", side_effect=GeminiError("boom")), mock.patch("web_search.ddg_search") as ddg, \
                self.assertRaises(ToolExecutionError) as cm:
            tool_executor.search_web({"query": "x"})
        ddg.assert_not_called()
        self.assertIn("boom", str(cm.exception))

    def test_everything_fails_with_both_reasons(self):
        with mock.patch("tool_executor.ask_gemini", side_effect=GeminiError("429 limit")), \
                mock.patch("web_search.ddg_search", side_effect=web_search.SearchError("no results found")), self.assertRaises(ToolExecutionError) as cm:
            tool_executor.search_web({"query": "x"})
        self.assertIn("429 limit", str(cm.exception))
        self.assertIn("no results found", str(cm.exception))
        with self.assertRaises(ToolExecutionError):
            tool_executor.search_web({})

    def test_configure_reads_settings(self):
        cfg = load_config()
        cfg["search"]["engine"], cfg["actions"]["root"] = "web", "never"
        tool_executor.configure(cfg)
        self.assertEqual((tool_executor.SEARCH["engine"], actions.SETTINGS["root"]), ("web", "never"))
        cfg["search"]["engine"], cfg["actions"]["root"] = "bogus", "bogus"
        tool_executor.configure(cfg)
        self.assertEqual((tool_executor.SEARCH["engine"], actions.SETTINGS["root"]), ("auto", "auto"))

    def test_settings_are_registered(self):
        for key, choices in (("search.engine", ("auto", "gemini", "web")), ("actions.root", ("auto", "always", "never")), ("wake.engine", ("auto", "gemini", "whisper"))):
            self.assertEqual(SETTINGS[key], (str, choices))
        cfg = load_config()
        self.assertEqual((cfg["wake"]["engine"], cfg["wake"]["end_silence_ms"], cfg["wake"]["online_per_min"]), ("auto", 500, 10))


class TestToolTables(unittest.TestCase):
    def test_new_tools_reach_every_model(self):
        gem = {t["function"]["name"]: t["function"] for t in gemini_tools.build_gemini_tools_schema()}
        local = {t["function"]["name"]: t["function"] for t in gemini_tools.build_local_tools_schema()}
        for name, param in (("open_url", "url"), ("open_app", "app"), ("youtube_play", "query")):
            for table in (gem, local):
                self.assertIn(name, table)
                self.assertEqual(table[name]["parameters"]["required"], [param])
        self.assertIn("channel", gem["youtube_play"]["parameters"]["properties"]["query"]["description"])
        from orchestrator import TOOL_INSTRUCTIONS
        self.assertIn("youtube_play(query)", TOOL_INSTRUCTIONS)
        self.assertIn("Never say you cannot", TOOL_INSTRUCTIONS)

    def test_executors_translate_errors(self):
        with mock.patch.object(actions, "youtube_play", side_effect=actions.ActionError("down")), self.assertRaises(ToolExecutionError) as cm:
            tool_executor.TOOL_EXECUTORS["youtube_play"]({"query": "x"})
        self.assertEqual(str(cm.exception), "down")
        with mock.patch.object(actions, "open_app", return_value="Opened x"):
            self.assertEqual(tool_executor.TOOL_EXECUTORS["open_app"]({"app": "x"}), "Opened x")


# ---------------------------------------------------------------- online wake word

class TestOnlineWake(unittest.TestCase):
    def setUp(self):
        self.cfg = load_config()
        self.env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"})
        self.env.start()
        self.addCleanup(self.env.stop)
        stt._ONLINE.update(blocked_until=0.0, recent=deque(maxlen=64))
        self.t = [1000.0]
        self.now = lambda: self.t[0]

    def go(self, online, engine="auto", whisper=("whisper words", True), **kw):
        self.cfg["wake"]["engine"] = engine
        with mock.patch.object(stt, "available", return_value=whisper[1]), mock.patch.object(stt, "transcribe_wav", return_value=whisper[0]) as w:
            try:
                return stt.transcribe_wake(b"wav", self.cfg, now=self.now, online=online, **kw), w
            except stt.TranscribeError as e:
                return e, w

    def test_auto_prefers_online(self):
        text, w = self.go(lambda wav: "Hey Faze")
        self.assertEqual(text, "Hey Faze")
        w.assert_not_called()

    def test_quota_error_falls_back_to_whisper_and_pauses_online(self):
        def boom(wav):
            raise GeminiError("429 quota")
        text, w = self.go(boom)
        self.assertEqual(text, "whisper words")
        text, w = self.go(lambda wav: self.fail("online must be paused"))
        self.assertEqual(text, "whisper words")
        self.t[0] += 301
        text, _ = self.go(lambda wav: "back online")
        self.assertEqual(text, "back online")

    def test_engine_modes(self):
        text, _ = self.go(lambda wav: self.fail("offline only"), engine="whisper")
        self.assertEqual(text, "whisper words")
        err, w = self.go(lambda wav: (_ for _ in ()).throw(GeminiError("network down")), engine="gemini")
        self.assertIsInstance(err, stt.TranscribeError)
        w.assert_not_called()
        err, _ = self.go(lambda wav: "x", engine="auto", whisper=("", False))
        self.assertEqual(err, "x")
        err, _ = self.go(lambda wav: (_ for _ in ()).throw(GeminiError("network down")), whisper=("", False))
        self.assertIsInstance(err, stt.TranscribeError)
        self.assertIn("no speech engine", str(err))

    def test_rate_cap_protects_the_quota(self):
        self.cfg["wake"]["online_per_min"] = 3
        calls = []
        for _ in range(5):
            self.go(lambda wav: calls.append(1) or "x")
        self.assertEqual(len(calls), 3)  # the other two used whisper
        self.t[0] += 61
        self.go(lambda wav: calls.append(1) or "x")
        self.assertEqual(len(calls), 4)

    def test_readiness_and_noise_cleanup(self):
        with mock.patch.object(stt, "available", return_value=False):
            self.assertTrue(stt.wake_ready(self.cfg))
            self.cfg["wake"]["engine"] = "whisper"
            self.assertFalse(stt.wake_ready(self.cfg))
            self.cfg["wake"]["engine"] = "auto"
            with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
                self.assertFalse(stt.wake_ready(self.cfg))
        text, _ = self.go(lambda wav: "[BLANK_AUDIO]")
        self.assertEqual(text, "")

    def test_gemini_transcribe_request(self):
        sent = {}

        def post(url, headers=None, json=None, timeout=None):
            sent.update(url=url, body=json, headers=headers)
            return mock.Mock(status_code=200, json=lambda: {"candidates": [{"content": {"parts": [{"text": " hey faze "}]}}]})
        with mock.patch("requests.post", side_effect=post):
            self.assertEqual(gemini_client.transcribe_audio(b"RIFFdata"), "hey faze")
        parts = sent["body"]["contents"][0]["parts"]
        self.assertEqual(parts[1]["inlineData"], {"mimeType": "audio/wav", "data": base64.b64encode(b"RIFFdata").decode()})
        self.assertEqual(sent["headers"]["x-goog-api-key"], "k")
        with mock.patch("requests.post", return_value=mock.Mock(status_code=200, json=lambda: {"candidates": [{"content": {"parts": []}}]})):
            self.assertEqual(gemini_client.transcribe_audio(b"x"), "")
        with mock.patch("requests.post", return_value=mock.Mock(status_code=429, json=lambda: {"error": {"message": "quota"}}, text="quota", headers={})), \
                self.assertRaises(GeminiError):
            gemini_client.transcribe_audio(b"x")
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}), self.assertRaises(GeminiError):
            gemini_client.transcribe_audio(b"x")

    def test_listener_uses_the_configured_pause_and_reports_engine(self):
        cfg = load_config()
        cfg["wake"]["end_silence_ms"] = 300
        listener = wake.WakeListener(cfg, on_wake=lambda *a: None)
        self.assertEqual(listener.status()["engine"], "auto")
        self.assertEqual(listener.transcribe, stt.transcribe_wake)


# ---------------------------------------------------------------- service script and launcher

class TestService(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="faze-svc-")
        self.fake = os.path.join(self.dir, "fake_faze.sh")
        self.script = os.path.join(ROOT, "scripts", "faze_service.sh")
        self.env = dict(os.environ, FAZE_STATE=os.path.join(self.dir, "state"), FAZE_LOG=os.path.join(self.dir, "faze.log"),
                        FAZE_CMD=self.fake, FAZE_RESTART_DELAY="0.2", PATH=os.environ["PATH"])
        self.addCleanup(lambda: self.run_svc("stop"))

    def write_fake(self, body):
        with open(self.fake, "w") as f:
            f.write("#!/bin/bash\n" + body)
        os.chmod(self.fake, 0o755)

    def log(self):
        try:
            return open(self.env["FAZE_LOG"]).read()
        except FileNotFoundError:
            return ""

    def run_svc(self, arg):
        return subprocess.run(["bash", self.script, arg], capture_output=True, text=True, env=self.env, timeout=20)

    def wait_for(self, predicate, seconds=6):
        end = time.time() + seconds
        while time.time() < end:
            if predicate():
                return True
            time.sleep(0.1)
        return False

    def test_start_status_stop(self):
        self.write_fake("echo started >> \"$FAZE_LOG\"; sleep 60\n")
        self.assertEqual(self.run_svc("status").returncode, 1)
        out = self.run_svc("start")
        self.assertIn("started (pid", out.stdout)
        self.assertTrue(self.wait_for(lambda: self.run_svc("status").returncode == 0))
        self.assertIn("already running", self.run_svc("start").stdout)
        self.assertTrue(self.wait_for(lambda: "started" in self.log()))
        self.assertIn("stopped", self.run_svc("stop").stdout)
        self.assertTrue(self.wait_for(lambda: self.run_svc("status").returncode == 1))
        self.assertFalse(os.path.exists(os.path.join(self.env["FAZE_STATE"], "faze.pid")))

    def test_crashes_are_restarted(self):
        self.write_fake("echo run >> \"$FAZE_LOG\"; exit 3\n")
        self.run_svc("start")
        self.assertTrue(self.wait_for(lambda: self.log().count("run\n") >= 3))
        log = self.log()
        self.assertIn("faze exited (code 3); restarting", log)
        self.run_svc("stop")
        time.sleep(0.6)
        count = self.log().count("run\n")
        time.sleep(0.8)
        self.assertEqual(self.log().count("run\n"), count)  # stopped means stopped

    def test_boot_script_uses_the_service(self):
        text = open(os.path.join(ROOT, "scripts", "install_boot.sh")).read()
        self.assertIn("faze_service.sh\" start", text)
        self.assertNotIn("--serve", text)

    def test_web_app_is_the_default(self):
        src = open(os.path.join(CORE, "orchestrator.py")).read()
        self.assertIn('if "--cli" not in sys.argv[1:]:', src)
        self.assertNotIn('"--serve" in sys.argv', src)
        self.assertIn("--cli", open(os.path.join(ROOT, "faze.sh")).read())


# ---------------------------------------------------------------- doctor

class TestDoctor(unittest.TestCase):
    def run_doctor(self, key="k", **patches):
        cfg = load_config()
        env = {"GEMINI_API_KEY": key}
        with mock.patch.dict(os.environ, env), mock.patch("gemini_client.ask_gemini", **patches.get("gemini", {"return_value": {"type": "text", "content": "OK"}})), \
                mock.patch("web_search.ddg_search", return_value=[{"title": "Python", "url": "u", "snippet": ""}]), \
                mock.patch.object(actions, "youtube_find", return_value=("aaaaaaaaaaa", "A video")), \
                mock.patch("tts.synthesize_audio", return_value=(b"x" * 10, "audio/wav")), \
                mock.patch.object(stt, "available", return_value=True), \
                mock.patch("live_client.check", return_value=[("websocket", True, "stubbed")]):
            return {name: (status, detail) for name, status, detail in doctor.run_checks(cfg)}

    def test_report_marks_each_check(self):
        res = self.run_doctor()
        self.assertEqual(res["Gemini key"][0], "ok")
        self.assertEqual(res["Gemini chat"][0], "ok")
        self.assertEqual(res["Gemini tool calling"][0], "warn")  # the stub answers in text
        self.assertEqual(res["Backup web search"][0], "ok")
        self.assertIn("1 results", res["Backup web search"][1])
        self.assertEqual(res["YouTube lookup"][0], "ok")
        self.assertEqual(res["Gemini voice"][0], "ok")
        self.assertEqual(res["Wake-word recognition"][0], "ok")

    def test_tool_writing_is_checked_for_a_create_tool_call(self):
        res = self.run_doctor(gemini={"return_value": {"type": "function_call", "name": "create_tool", "args": {}}})
        self.assertEqual(res["Gemini tool calling"], ("ok", mock.ANY))
        self.assertEqual(res["Gemini writes tools"][0], "ok")

    def test_failures_carry_the_real_error_and_no_key_skips(self):
        res = self.run_doctor(gemini={"side_effect": GeminiError("429 quota exceeded, retry in 12s")})
        self.assertEqual(res["Gemini chat"][0], "fail")
        self.assertIn("429 quota exceeded", res["Gemini chat"][1])
        self.assertEqual(res["Gemini web search"][0], "fail")
        res = self.run_doctor(key="")
        self.assertEqual(res["Gemini key"][0], "fail")
        for name in ("Gemini chat", "Gemini tool calling", "Gemini web search", "Gemini voice"):
            self.assertEqual(res[name][0], "skip", name)

    def test_format_and_command(self):
        text = doctor.format_report([("A", "ok", "fine"), ("B", "fail", "broken (0.1s)"), ("C", "skip", "needs the key")])
        self.assertEqual(text.splitlines(), ["[ok  ] A: fine", "[FAIL] B: broken (0.1s)", "[skip] C: needs the key", "3 checks, 1 failed"])
        self.assertEqual(doctor.format_report([("A", "ok", "x")]).splitlines()[-1], "1 checks, none failed")
        from orchestrator import COMMANDS
        self.assertIn("doctor", [c["name"] for c in COMMANDS])

    def test_a_crashing_check_cannot_break_the_report(self):
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"}), mock.patch("gemini_client.ask_gemini", side_effect=RuntimeError("weird")), \
                mock.patch("web_search.ddg_search", side_effect=KeyError("x")), mock.patch.object(actions, "youtube_find", side_effect=ValueError("y")), \
                mock.patch("tts.synthesize_audio", side_effect=OSError("z")), \
                mock.patch("live_client.check", return_value=[("websocket", True, "stubbed")]):
            res = doctor.run_checks(load_config())
        self.assertGreaterEqual(len(res), 10)
        self.assertTrue(all(len(r) == 3 for r in res))
        self.assertIn("RuntimeError: weird", [d for n, s, d in res if n == "Gemini chat"][0])


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestEngineSetting(unittest.TestCase):
    def test_voice_tab_has_an_engine_choice(self):
        res = run_smoke()
        self.assertNotIn("crash", res, res.get("crash"))
        self.assertEqual(res["errors"], [])
        self.assertEqual(res["settings"]["engine"], "auto")
        self.assertEqual(res["settings"]["engineSaved"], ["gemini"])


if __name__ == "__main__":
    unittest.main()
