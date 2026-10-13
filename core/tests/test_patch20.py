"""
Patch 20: a weather tool that does not depend on search quota (Open-Meteo), answered by the regex layer with no
model call; a sturdier screen reader for phones where uiautomator prints nothing; a yt-dlp fallback for YouTube.

    PYTHONPATH=. python3 -m unittest tests.test_patch20 -v
"""

import os
import sys
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)

from tests.test_patch1 import Base  # noqa: E402

import actions  # noqa: E402
import config_loader  # noqa: E402
import device  # noqa: E402
import direct_tool_router  # noqa: E402
import gemini_tools  # noqa: E402
import permission_policy  # noqa: E402
import tool_executor  # noqa: E402
import tool_schema  # noqa: E402
import weather  # noqa: E402

GEOCODE_OK = {"results": [{"name": "Lagos", "country": "Nigeria", "latitude": 6.45, "longitude": 3.4, "timezone": "Africa/Lagos"}]}
FORECAST_OK = {
    "current": {"temperature_2m": 30.6, "apparent_temperature": 35.2, "relative_humidity_2m": 78, "weather_code": 2, "wind_speed_10m": 11.6},
    "daily": {"temperature_2m_max": [32.1], "temperature_2m_min": [25.0], "precipitation_probability_max": [40]},
}
EXPECTED = ("Lagos, Nigeria: 31°C (feels like 35°C), partly cloudy, humidity 78%, wind 12 km/h. "
            "Today: high 32°C, low 25°C, 40% chance of rain.")


class FakeResp:
    def __init__(self, data, status=200):
        self.data, self.status_code = data, status

    def json(self):
        if isinstance(self.data, Exception):
            raise self.data
        return self.data


class FakeNet:
    def __init__(self, geocode=GEOCODE_OK, forecast=FORECAST_OK, status=200):
        self.geocode, self.forecast, self.status, self.calls = geocode, forecast, status, []

    def __call__(self, url, params=None, timeout=None):
        self.calls.append((url, params))
        return FakeResp(self.geocode if url == weather.GEOCODE else self.forecast, self.status)


class WeatherBase(unittest.TestCase):
    def setUp(self):
        weather._places.clear()
        weather._forecasts.clear()
        self.addCleanup(weather._places.clear)
        self.addCleanup(weather._forecasts.clear)


class TestWeatherModule(WeatherBase):
    def test_reads_like_a_sentence(self):
        net = FakeNet()
        self.assertEqual(weather.get_weather("Lagos", get=net), EXPECTED)
        self.assertEqual([c[0] for c in net.calls], [weather.GEOCODE, weather.FORECAST])
        self.assertEqual(net.calls[0][1]["name"], "Lagos")
        self.assertEqual((net.calls[1][1]["latitude"], net.calls[1][1]["forecast_days"]), (6.45, 1))

    def test_places_and_forecasts_are_cached(self):
        net, clock = FakeNet(), [100.0]
        weather.get_weather("Lagos", get=net, now=lambda: clock[0])
        clock[0] += 60
        weather.get_weather("lagos", get=net, now=lambda: clock[0])
        self.assertEqual(len(net.calls), 2)  # nothing new fetched within ten minutes
        clock[0] += weather.CACHE_SECONDS
        weather.get_weather("Lagos", get=net, now=lambda: clock[0])
        self.assertEqual([c[0] for c in net.calls].count(weather.FORECAST), 2)
        self.assertEqual([c[0] for c in net.calls].count(weather.GEOCODE), 1)  # the place itself is kept

    def test_no_place_named(self):
        with self.assertRaisesRegex(weather.WeatherError, "which place"):
            weather.get_weather("  ", get=FakeNet())

    def test_unknown_place(self):
        with self.assertRaisesRegex(weather.WeatherError, "couldn't find a place called Atlantis"):
            weather.get_weather("Atlantis", get=FakeNet(geocode={}))

    def test_service_trouble_is_reported_plainly(self):
        with self.assertRaisesRegex(weather.WeatherError, "answered 503"):
            weather.get_weather("Lagos", get=FakeNet(status=503))
        import requests

        def down(*a, **k):
            raise requests.ConnectionError("no route")
        with self.assertRaisesRegex(weather.WeatherError, "could not reach the weather service"):
            weather.get_weather("Lagos", get=down)
        with self.assertRaisesRegex(weather.WeatherError, "unreadable"):
            weather.get_weather("Lagos", get=FakeNet(geocode=ValueError("bad json")))

    def test_missing_conditions_and_odd_codes(self):
        with self.assertRaisesRegex(weather.WeatherError, "no current conditions"):
            weather.get_weather("Lagos", get=FakeNet(forecast={"current": {}}))
        weather._forecasts.clear()
        odd = {"current": dict(FORECAST_OK["current"], weather_code=999), "daily": {}}
        text = weather.get_weather("Lagos", get=FakeNet(forecast=odd))
        self.assertIn("unknown conditions", text)
        self.assertNotIn("Today:", text)  # no daily numbers, no made-up ones


class TestWeatherTool(WeatherBase):
    def tearDown(self):
        tool_executor.WEATHER["location"] = ""

    def test_registered_everywhere_a_tool_must_be(self):
        self.assertEqual(tool_schema.TOOL_REGISTRY["get_weather"], {"city": str})
        self.assertEqual(permission_policy.PERMISSION_REGISTRY["get_weather"], "safe")
        self.assertIn("get_weather", tool_executor.TOOL_EXECUTORS)
        self.assertIn("weather", gemini_tools.TOOL_DESCRIPTIONS["get_weather"].lower())
        self.assertIn("city", gemini_tools.PARAM_DESCRIPTIONS)
        for schema in (gemini_tools.build_gemini_tools_schema(), gemini_tools.build_local_tools_schema()):
            self.assertIn("get_weather", [t["function"]["name"] for t in schema])
        self.assertTrue(tool_schema.validate_tool_call({"tool": "get_weather", "parameters": {"city": ""}}))

    def test_empty_city_uses_the_setting(self):
        tool_executor.WEATHER["location"] = "Lagos"
        with mock.patch("weather.get_weather", return_value="ok") as g:
            self.assertEqual(tool_executor.get_weather({"city": ""}), "ok")
            g.assert_called_once_with("Lagos")
            g.reset_mock()
            tool_executor.get_weather({"city": "Abuja"})
            g.assert_called_once_with("Abuja")  # a named place beats the setting

    def test_no_city_and_no_setting_asks(self):
        with self.assertRaisesRegex(tool_executor.ToolExecutionError, "which place"):
            tool_executor.get_weather({"city": ""})

    def test_errors_are_tool_errors(self):
        with mock.patch("weather.get_weather", side_effect=weather.WeatherError("the weather service answered 500")):
            with self.assertRaisesRegex(tool_executor.ToolExecutionError, "Weather lookup failed: the weather service answered 500"):
                tool_executor.get_weather({"city": "Lagos"})

    def test_configure_reads_the_setting(self):
        cfg = config_loader.load_config()
        self.assertEqual(cfg["weather"], {"location": ""})
        self.assertEqual(config_loader.SETTINGS["weather.location"], (str, None))
        cfg["weather"]["location"] = "  Port Harcourt "
        tool_executor.configure(cfg)
        self.assertEqual(tool_executor.WEATHER["location"], "Port Harcourt")


class TestWeatherRoute(unittest.TestCase):
    def test_routes_plain_weather_questions(self):
        for msg, city in (("What's the weather looking like for today?", ""), ("weather in Port Harcourt?", "Port Harcourt"),
                          ("how is the weather in lagos right now", "lagos"), ("what's the weather like outside", ""),
                          ("Weather", ""), ("what is the weather in New York today", "New York")):
            self.assertEqual(direct_tool_router.try_direct_route(msg), {"tool": "get_weather", "parameters": {"city": city}}, msg)

    def test_leaves_everything_else_to_the_model(self):
        for msg in ("weather tomorrow", "what was the weather like last week", "why is the weather app slow",
                    "what's the weather in Paris and then play some music", "average weather in Lagos in March",
                    "explain how weather forecasting models work in detail please and also compare them to climate models",
                    "what's the time"):
            call = direct_tool_router.try_direct_route(msg)
            self.assertNotEqual((call or {}).get("tool"), "get_weather", msg)

    def test_older_routes_still_win_where_they_always_did(self):
        self.assertEqual(direct_tool_router.try_direct_route("what's my battery")["tool"], "get_battery_status")
        self.assertEqual(direct_tool_router.try_direct_route("set volume to 7"), {"tool": "set_volume", "parameters": {"level": 7}})


class TestWeatherThroughTheConversation(Base):
    def test_answered_by_the_regex_layer_without_any_model(self):
        c = self.convo()
        with mock.patch("weather.get_weather", return_value=EXPECTED) as g:
            self.assertEqual(c.send("What's the weather looking like for today?"), EXPECTED)
        g.assert_called_once_with("")  # the empty city is resolved by the tool, from the setting
        self.assertEqual(self.llama.requests, [])

    def test_the_default_place_comes_from_the_setting(self):
        c = self.convo()
        self.addCleanup(lambda: tool_executor.WEATHER.update(location=""))
        c.handle_command("/settings set weather.location Lagos")
        self.addCleanup(lambda: c.reset_setting("weather.location"))
        with mock.patch("weather.get_weather", return_value=EXPECTED) as g:
            c.send("what's the weather like")
        g.assert_called_once_with("Lagos")

    def test_a_failure_is_said_not_guessed(self):
        c = self.convo()
        with mock.patch("weather.get_weather", side_effect=weather.WeatherError("could not reach the weather service (ConnectionError)")):
            reply = c.send("weather in Lagos")
        self.assertIn("could not reach the weather service", reply)
        self.assertEqual(self.llama.requests, [])


# ---------------------------------------------------------------- the screen reader

HIERARCHY = ('<?xml version="1.0"?><hierarchy rotation="0"><node text="Settings" class="android.widget.TextView" package="com.x" '
             'clickable="true" resource-id="a:id/t" bounds="[0,0][200,100]"/></hierarchy>')


class TestReadNodes(unittest.TestCase):
    def fake_root(self, script):
        calls = []

        def root(cmd, timeout=15.0, cap=4000):
            calls.append(list(cmd))
            return script(list(cmd))
        return root, calls

    def test_the_normal_path_is_one_dump(self):
        def script(cmd):
            if cmd[:2] == ["uiautomator", "dump"]:
                return "UI hierchary dumped to: /data/local/tmp/faze_ui.xml"
            return HIERARCHY if cmd[0] == "cat" else ""
        root, calls = self.fake_root(script)
        with mock.patch.object(device, "_root", root):
            nodes = device.read_nodes()
        self.assertEqual(nodes[0]["label"], "Settings")
        self.assertEqual(len([c for c in calls if c[:2] == ["uiautomator", "dump"]]), 1)

    def test_a_silent_dump_that_wrote_the_file_counts(self):
        def script(cmd):
            return HIERARCHY if cmd[0] == "cat" else ""  # uiautomator prints nothing, the file exists
        root, _ = self.fake_root(script)
        with mock.patch.object(device, "_root", root):
            self.assertEqual(device.read_nodes()[0]["label"], "Settings")

    def test_compressed_is_tried_second(self):
        state = {"dumps": 0}

        def script(cmd):
            if cmd[:2] == ["uiautomator", "dump"]:
                state["dumps"] += 1
                return "" if "--compressed" not in cmd else "UI hierchary dumped to: x"
            if cmd[0] == "cat":
                return HIERARCHY if state["dumps"] >= 2 else ""
            return ""
        root, calls = self.fake_root(script)
        with mock.patch.object(device, "_root", root):
            self.assertEqual(device.read_nodes()[0]["label"], "Settings")
        self.assertIn(["uiautomator", "dump", "--compressed", "/data/local/tmp/faze_ui.xml"], calls)

    def test_stdout_is_the_last_resort(self):
        def script(cmd):
            if cmd == ["uiautomator", "dump", "/dev/stdout"]:
                return "junk before " + HIERARCHY + "\nUI hierchary dumped to: /dev/stdout"
            if cmd[0] == "cat":
                raise device.DeviceError("cat exited 1: No such file")
            return ""
        root, calls = self.fake_root(script)
        with mock.patch.object(device, "_root", root):
            self.assertEqual(device.read_nodes()[0]["label"], "Settings")
        self.assertEqual(calls[-1], ["uiautomator", "dump", "/dev/stdout"])

    def test_total_failure_says_what_every_try_said(self):
        def script(cmd):
            if cmd[:2] == ["uiautomator", "dump"]:
                return "ERROR: null root node returned by UiTestAutomationBridge."
            if cmd[0] == "cat":
                raise device.DeviceError("cat exited 1: No such file")
            return ""
        root, _ = self.fake_root(script)
        with mock.patch.object(device, "_root", root):
            with self.assertRaises(device.DeviceError) as cm:
                device.read_nodes()
        self.assertIn("null root node", str(cm.exception))
        self.assertIn("[3 tries;", str(cm.exception))
        self.assertIn("screen_look", str(cm.exception))

    def test_a_silent_failure_is_still_reported(self):
        def script(cmd):
            if cmd[0] == "cat":
                raise device.DeviceError("cat exited 1: No such file")
            return ""
        root, _ = self.fake_root(script)
        with mock.patch.object(device, "_root", root):
            with self.assertRaisesRegex(device.DeviceError, "no output, no file"):
                device.read_nodes()


# ---------------------------------------------------------------- YouTube

class TestYoutubeFallback(unittest.TestCase):
    def test_ytdlp_find(self):
        with mock.patch("shutil.which", return_value="/bin/yt-dlp"), \
                mock.patch("tool_helpers.run", return_value="dQw4w9WgXcQ\tNever Gonna Give You Up\n") as run:
            self.assertEqual(actions._ytdlp_find("rick roll", False), ("dQw4w9WgXcQ", "Never Gonna Give You Up"))
            self.assertEqual(run.call_args[0][0][-1], "ytsearch1:rick roll")
            actions._ytdlp_find("mkbhd", True)
            self.assertEqual(run.call_args[0][0][-1], "ytsearchdate1:mkbhd")

    def test_ytdlp_find_gives_up_quietly(self):
        with mock.patch("shutil.which", return_value=None), mock.patch("tool_helpers.run", side_effect=AssertionError("not installed")):
            self.assertIsNone(actions._ytdlp_find("x", False))
        with mock.patch("shutil.which", return_value="/bin/yt-dlp"):
            with mock.patch("tool_helpers.run", side_effect=RuntimeError("yt-dlp timed out")):
                self.assertIsNone(actions._ytdlp_find("x", False))
            with mock.patch("tool_helpers.run", return_value="not-an-id\tTitle"):
                self.assertIsNone(actions._ytdlp_find("x", False))
            with mock.patch("tool_helpers.run", return_value=""):
                self.assertIsNone(actions._ytdlp_find("x", False))

    def test_find_uses_ytdlp_only_when_the_page_fails(self):
        with mock.patch.object(actions, "_fetch", side_effect=actions.ActionError("YouTube answered 429")), \
                mock.patch.object(actions, "_ytdlp_find", return_value=("abcdefghijk", "From yt-dlp")) as y:
            self.assertEqual(actions.youtube_find("cats"), ("abcdefghijk", "From yt-dlp"))
            y.assert_called_once_with("cats", False)
        with mock.patch.object(actions, "_fetch", return_value="no video data here"), \
                mock.patch.object(actions, "_ytdlp_find", return_value=("abcdefghijk", "From yt-dlp")):
            self.assertEqual(actions.youtube_find("cats")[0], "abcdefghijk")
        page = '"videoId":"AAAAAAAAAAA","title":{"runs":[{"text":"Page hit"}]}'
        with mock.patch.object(actions, "_fetch", return_value=page), \
                mock.patch.object(actions, "_ytdlp_find", side_effect=AssertionError("page worked")):
            self.assertEqual(actions.youtube_find("cats")[0], "AAAAAAAAAAA")

    def test_play_falls_back_to_the_search_page_when_nothing_finds_a_video(self):
        with mock.patch.object(actions, "youtube_find", return_value=None), mock.patch.object(actions, "open_url") as opened:
            self.assertIn("Opened YouTube search", actions.youtube_play("cats"))
            self.assertIn("search_query=cats", opened.call_args[0][0])

    def test_doctor_hint(self):
        with open(os.path.join(CORE, "doctor.py")) as f:
            self.assertIn("pip install yt-dlp", f.read())


if __name__ == "__main__":
    unittest.main()
