"""
Agent: Gemini-written tools, approval modes (ask / yolo / off), higher-level gating, saved tasks, plus the
model picker, mic source choice and Gemini audio packaging fixes.

    PYTHONPATH=. python3 -m unittest tests.test_patch10 -v
"""
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

CORE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CORE)
sys.path.insert(0, os.path.join(os.path.dirname(CORE), "scripts"))

import agent  # noqa: E402
import gemini_tools  # noqa: E402
import pa_record  # noqa: E402
import tts  # noqa: E402
from config_loader import SETTINGS  # noqa: E402
from tests.test_patch1 import Base  # noqa: E402
from tests.test_patch5 import WebBase  # noqa: E402
from tests.test_patch8 import HAVE_JSDOM, node, run_smoke  # noqa: E402
from tool_executor import ToolExecutionError  # noqa: E402
from tool_schema import TOOL_REGISTRY, validate_tool_call  # noqa: E402

MILES = 'def run(args):\n    return "%.2f" % (float(args["miles"]) * 1.609344)\n'
PARAMS = '{"miles": {"type": "number", "description": "distance in miles"}}'


def spec(**over):
    base = {"name": "miles_to_km", "description": "Convert miles to kilometres.", "how_it_works": "Multiplies the distance by 1.609344.",
            "params_json": PARAMS, "code": MILES, "level": "normal"}
    base.update(over)
    return base


class AgentCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="faze-agent-")
        self.env = mock.patch.dict(os.environ, {"FAZE_AGENT_DIR": self.dir})
        self.env.start()
        self.store = agent.use_store(self.dir)
        agent.set_policy(high_tools=False)
        self.addCleanup(self.env.stop)
        self.addCleanup(lambda: agent.use_store(tempfile.mkdtemp(prefix="faze-agent-empty-")))


class TestCodeChecks(AgentCase):
    def test_pure_code_is_normal(self):
        r = agent.assess_code(MILES + "import math\nimport json\nfrom urllib.parse import quote\n")
        self.assertEqual((r["ok"], r["level"], r["reasons"]), (True, "normal", []))

    def test_risky_code_is_high_with_reasons(self):
        r = agent.assess_code("import subprocess\nfrom os import path\ndef run(args):\n    return str(eval('1'))\n")
        self.assertTrue(r["ok"])
        self.assertEqual(r["level"], "high")
        self.assertEqual(r["reasons"], ["imports subprocess", "imports from os", "calls eval()"])
        self.assertIn("uses __class__", agent.assess_code("def run(a):\n    return a.__class__\n")["reasons"])
        self.assertEqual(agent.assess_code("def run(a):\n    return open('/x').read()\n")["reasons"], ["calls open()"])

    def test_rejections(self):
        for code, word in [("", "no code"), ("def run(:", "syntax"), ("def go(a): pass", "entry point"), ("def run(a, b): pass", "entry point"),
                           ("def run(a):\n    return 'GEMINI_API_KEY'", "API key"), ("x=1\n" * 5000, "over")]:
            r = agent.assess_code(code)
            self.assertFalse(r["ok"], code[:20])
            self.assertIn(word, r["error"])

    def test_params(self):
        self.assertEqual(agent.normalize_params(PARAMS), {"miles": {"type": "number", "description": "distance in miles"}})
        self.assertEqual(agent.normalize_params('{"a": "string"}')["a"]["type"], "string")
        self.assertEqual(agent.normalize_params(""), {})
        for bad in ("not json", '{"A": "string"}', '{"a": "list"}', json.dumps({f"p{i}": "string" for i in range(7)}), "[1]"):
            with self.assertRaises(agent.ToolCreateError, msg=bad):
                agent.normalize_params(bad)

    def test_prepare_tool_validation_and_levels(self):
        info, meta, code = agent.prepare_tool(spec(), high_allowed=False)
        self.assertEqual((info["level"], meta["level"], info["params"], info["replaces"]), ("normal", "normal", {"miles": "number"}, False))
        for bad in (spec(name="x"), spec(name="Bad-Name"), spec(name="search_web"), spec(name="create_tool"), spec(description=""),
                    spec(how_it_works=""), spec(code="def go(): pass")):
            with self.assertRaises(agent.ToolCreateError):
                agent.prepare_tool(bad, True)
        with self.assertRaises(agent.ToolCreateError) as cm:
            agent.prepare_tool(spec(level="high"), high_allowed=False)
        self.assertIn("/elevated on", str(cm.exception))
        risky = spec(code="import subprocess\ndef run(args):\n    return 'x'\n")
        with self.assertRaises(agent.ToolCreateError):
            agent.prepare_tool(risky, False)
        info, _, _ = agent.prepare_tool(risky, True)  # honest declaration or not, the scan decides
        self.assertEqual((info["level"], info["reasons"]), ("high", ["imports subprocess"]))
        self.assertIn("Higher-level because it imports subprocess", agent.format_info(info))


class TestStore(AgentCase):
    def add(self, **over):
        info, meta, code = agent.prepare_tool(spec(**over), True)
        return self.store.add_tool(meta, code)

    def test_add_registers_everywhere_and_runs(self):
        self.add()
        self.assertEqual(TOOL_REGISTRY["miles_to_km"], {"miles": float})
        self.assertEqual(PERMISSION_REGISTRY_OF()["miles_to_km"], "safe")
        self.assertTrue(validate_tool_call({"tool": "miles_to_km", "parameters": {"miles": 10}}))  # int is a fine number
        decl = [t for t in gemini_tools.build_gemini_tools_schema() if t["function"]["name"] == "miles_to_km"][0]["function"]
        self.assertEqual(decl["parameters"]["properties"]["miles"], {"type": "number", "description": "distance in miles"})
        self.assertIn("Convert miles", decl["description"])
        local = {t["function"]["name"] for t in agent.local_schema()}
        self.assertIn("miles_to_km", local)
        self.assertNotIn("create_tool", local)
        from tool_executor import execute_tool
        self.assertEqual(execute_tool("miles_to_km", {"miles": 10}), "16.09")
        self.assertEqual(self.store.tools["miles_to_km"]["uses"], 1)
        self.assertIn("miles_to_km(miles)", agent.custom_tools_text())

    def test_persists_and_reloads(self):
        self.add()
        self.store.save_task("convert miles to km", [{"tool": "miles_to_km", "args": {"miles": 3}, "ok": True}], "4.83 km")
        again = agent.use_store(self.dir)
        self.assertIn("miles_to_km", again.tools)
        self.assertIn("miles_to_km", TOOL_REGISTRY)
        self.assertEqual(len(again.tasks), 1)
        os.remove(os.path.join(self.dir, "tools", "miles_to_km.py"))  # a deleted file drops the tool
        self.assertEqual(agent.use_store(self.dir).tools, {})
        self.assertNotIn("miles_to_km", TOOL_REGISTRY)

    def test_runner_limits_and_scrubbed_environment(self):
        self.add(name="spin_forever", code="def run(args):\n    while True:\n        pass\n", params_json="{}")
        with self.assertRaises(ToolExecutionError) as cm:
            self.store.run_tool("spin_forever", {}, timeout=2)
        self.assertIn("timed out", str(cm.exception))
        self.add(name="boom", code="def run(args):\n    return 1 / 0\n", params_json="{}")
        with self.assertRaises(ToolExecutionError) as cm:
            self.store.run_tool("boom", {})
        self.assertIn("ZeroDivisionError", str(cm.exception))
        self.add(name="peek_env", code="import os\ndef run(args):\n    return ','.join(sorted(k for k in os.environ if 'KEY' in k or 'TOKEN' in k)) or 'clean'\n", params_json="{}")
        agent.set_policy(high_tools=True)
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": "secret", "FAZE_TOKEN": "t"}):
            self.assertEqual(self.store.run_tool("peek_env", {}), "clean")
        self.add(name="chatty", code="def run(args):\n    return 'x' * 100000\n", params_json="{}")
        self.assertEqual(len(self.store.run_tool("chatty", {})), agent.OUTPUT_CAP)
        self.add(name="dict_out", code="def run(args):\n    return {'a': 1}\n", params_json="{}")
        self.assertEqual(self.store.run_tool("dict_out", {}), '{"a": 1}')

    def test_high_level_tools_only_run_while_enabled(self):
        self.add(name="shell_date", code="import subprocess\ndef run(args):\n    return 'ok'\n", params_json="{}", level="high")
        with self.assertRaises(ToolExecutionError) as cm:
            self.store.run_tool("shell_date", {})
        self.assertIn("/elevated on", str(cm.exception))
        agent.set_policy(high_tools=True)
        self.assertEqual(self.store.run_tool("shell_date", {}), "ok")
        agent.set_policy(high_tools=False)
        with self.assertRaises(ToolExecutionError):
            self.store.run_tool("shell_date", {})

    def test_update_keeps_use_count_remove_and_clear(self):
        self.add()
        self.store.run_tool("miles_to_km", {"miles": 1})
        self.add(description="Convert miles to km (v2).")
        self.assertEqual((self.store.tools["miles_to_km"]["uses"], len(self.store.tools)), (1, 1))
        self.assertTrue(self.store.remove_tool("miles_to_km"))
        self.assertFalse(self.store.remove_tool("miles_to_km"))
        self.assertNotIn("miles_to_km", TOOL_REGISTRY)
        self.add(name="tool_one", params_json="{}", code="def run(a):\n    return '1'\n")
        self.add(name="tool_two", params_json="{}", code="def run(a):\n    return '2'\n")
        self.assertEqual(self.store.clear_tools(), 2)
        self.assertEqual(os.listdir(os.path.join(self.dir, "tools")), [])
        self.assertEqual(agent.custom_tools_text(), "")

    def test_tool_cap_and_builtin_protection(self):
        for i in range(agent.MAX_TOOLS):
            self.add(name=f"tool_{i:02d}", params_json="{}", code="def run(a):\n    return 'x'\n")
        with self.assertRaises(agent.ToolCreateError):
            agent.prepare_tool(spec(name="one_too_many"), True)
        agent.prepare_tool(spec(name="tool_00"), True)  # replacing is fine
        self.assertIn("get_battery_status", TOOL_REGISTRY)
        self.assertNotIn("create_tool", TOOL_REGISTRY)


def PERMISSION_REGISTRY_OF():
    from permission_policy import PERMISSION_REGISTRY
    return PERMISSION_REGISTRY


class TestTasks(AgentCase):
    def steps(self, *names):
        return [{"tool": n, "args": {"q": "x"}, "ok": True} for n in names]

    def test_save_match_dedupe_and_hint(self):
        t = self.store.save_task("Convert 25 miles to km please", self.steps("miles_to_km"), "40.23 km")
        self.assertEqual(t["keywords"], ["convert", "km", "miles"])  # numbers are the variable part
        same = self.store.save_task("convert 7 miles to km", self.steps("miles_to_km"), "11.27 km")
        self.assertEqual((same["id"], same["runs"], len(self.store.tasks)), (t["id"], 2, 1))
        self.assertEqual(self.store.match_tasks("what is the weather"), [])
        self.assertEqual(self.store.match_tasks("convert 90 miles to km")[0]["id"], t["id"])
        hint = self.store.task_hint("convert 90 miles into km")
        self.assertIn("A similar request was solved before", hint)
        self.assertIn("miles_to_km", hint)
        self.assertIn("11.27 km", hint)
        self.assertEqual(self.store.tasks[0]["uses"], 1)
        self.assertEqual(self.store.task_hint("write a poem about the sea"), "")
        self.assertIsNone(self.store.save_task("12 34", [], "x"))

    def test_remove_clear_snapshot_and_cap(self):
        a = self.store.save_task("first thing alpha", self.steps("a_tool"), "r")
        self.store.save_task("second thing bravo", self.steps("b_tool", "c_tool"), "r")
        snap = self.store.snapshot()
        self.assertEqual([t["prompt"] for t in snap["tasks"]], ["second thing bravo", "first thing alpha"])
        self.assertEqual(snap["tasks"][0]["tools"], ["b_tool", "c_tool"])
        self.assertTrue(self.store.remove_task(a["id"]))
        self.assertFalse(self.store.remove_task(a["id"]))
        self.assertEqual(self.store.clear_tasks(), 1)
        for i in range(agent.MAX_TASKS + 5):
            self.store.save_task(f"unique topic{i} word{i} thing{i}", self.steps("t"), "r")
        self.assertEqual(len(self.store.tasks), agent.MAX_TASKS)


def scripted(replies, seen=None):
    queue = list(replies)

    def gem(system, history, tools_schema=None, **kw):
        if seen is not None:
            seen.append({"system": system, "history": [m["content"] for m in history], "tools": tools_schema})
        item = queue.pop(0) if queue else {"type": "text", "content": "done"}
        return item(tools_schema) if callable(item) else item
    return gem


def call(tool, args=None):
    return {"type": "function_call", "name": tool, "args": args or {}}


TEXT = lambda t: {"type": "text", "content": t}  # noqa: E731


class TestOrchestratorAgent(AgentCase, Base):
    def setUp(self):
        AgentCase.setUp(self)
        Base.setUp(self)

    def tearDown(self):
        Base.tearDown(self)

    def run_agent(self, mode, replies, approver=None, prompt="turn 10 miles into km", high=False):
        c = self.convo()
        c.config["agent"].update(mode=mode, high_tools=high)
        agent.set_policy(high_tools=high)
        c.approver = approver
        seen = []
        with mock.patch("orchestrator.ask_gemini", side_effect=scripted(replies, seen)):
            c.handle_command("/model gemini")
            reply = c.send(prompt)
        return c, reply, seen

    def test_yolo_creates_runs_and_describes(self):
        c, reply, seen = self.run_agent("yolo", [call("create_tool", spec()), call("miles_to_km", {"miles": 10}), TEXT("10 miles is 16.09 km")])
        self.assertEqual(reply, "10 miles is 16.09 km")
        self.assertIn("miles_to_km", c.local.__class__ and agent.STORE.tools)
        self.assertIn("[tool result from miles_to_km] 16.09", seen[-1]["history"][-1])
        self.assertTrue(any("create_tool" in t["function"]["name"] for t in seen[0]["tools"]))
        notes = c.drain_notices()
        self.assertTrue(any("Added a tool (yolo mode)" in n and "What it does: Convert miles" in n and "How it works: Multiplies" in n for n in notes), notes)
        self.assertEqual(c.drain_notices(), [])
        task = agent.STORE.tasks[0]
        self.assertEqual((task["created_tools"], [s["tool"] for s in task["steps"]]), (["miles_to_km"], ["miles_to_km"]))
        self.assertIn("CUSTOM TOOLS", c.messages[0]["content"])
        self.assertIn("miles_to_km(miles)", c.messages[0]["content"])
        self.assertEqual(len(seen), 3)

    def test_ask_mode_approve_deny_and_no_way_to_ask(self):
        asked = []
        c, reply, _ = self.run_agent("ask", [call("create_tool", spec()), call("miles_to_km", {"miles": 2}), TEXT("ok")],
                                     approver=lambda info: asked.append(info) or True)
        self.assertEqual((len(asked), asked[0]["name"], asked[0]["level"], asked[0]["replaces"]), (1, "miles_to_km", "normal", False))
        self.assertIn("def run", asked[0]["code"])
        self.assertIn("miles_to_km", agent.STORE.tools)
        self.assertEqual(c.drain_notices(), [])  # asked in person, no extra notice

        agent.STORE.clear_tools()
        agent.STORE.clear_tasks()
        c, reply, seen = self.run_agent("ask", [call("create_tool", spec()), TEXT("fine, skipping it")], approver=lambda info: False)
        self.assertEqual(reply, "fine, skipping it")
        self.assertNotIn("miles_to_km", agent.STORE.tools)
        self.assertIn("declined", seen[-1]["history"][-1])
        self.assertEqual(agent.STORE.tasks, [])  # nothing worked, nothing to save

        c, reply, seen = self.run_agent("ask", [call("create_tool", spec()), TEXT("no luck")], approver=None)
        self.assertNotIn("miles_to_km", agent.STORE.tools)
        self.assertIn("declined", seen[-1]["history"][-1])

    def test_off_hides_create_tool_and_keeps_two_rounds(self):
        c, reply, seen = self.run_agent("off", [call("create_tool", spec()), TEXT("no tools for you")])
        self.assertFalse(any(t["function"]["name"] == "create_tool" for t in (seen[0]["tools"] or [])))
        self.assertIn("not available", seen[1]["history"][-1])
        self.assertEqual(agent.STORE.tools, {})
        self.assertNotIn("create_tool", seen[0]["system"])

    def test_high_level_gating_at_creation(self):
        risky = spec(name="shell_thing", level="high", code="import subprocess\ndef run(args):\n    return 'x'\n", params_json="{}")
        c, reply, seen = self.run_agent("yolo", [call("create_tool", risky), TEXT("ok")], high=False)
        self.assertNotIn("shell_thing", agent.STORE.tools)
        self.assertIn("Higher-level tools are turned off", seen[-1]["history"][-1])
        c, reply, seen = self.run_agent("yolo", [call("create_tool", risky), TEXT("ok")], high=True)
        self.assertEqual(agent.STORE.tools["shell_thing"]["level"], "high")
        c.config["agent"]["high_tools"] = False
        agent.set_policy(high_tools=False)
        ok, text = c._execute_tool("shell_thing", {}, "gemini")
        self.assertFalse(ok)
        self.assertIn("/elevated on", text)

    def test_bad_code_goes_back_to_gemini_to_fix(self):
        broken = spec(code="def run(args:\n    return 1")
        c, reply, seen = self.run_agent("yolo", [call("create_tool", broken), call("create_tool", spec()), call("miles_to_km", {"miles": 1}), TEXT("1.61")])
        self.assertIn("syntax error", seen[1]["history"][-1])
        self.assertIn("Fix the problem", seen[1]["history"][-1])
        self.assertEqual(reply, "1.61")
        self.assertIn("miles_to_km", agent.STORE.tools)

    def test_agentic_turns_get_more_rounds(self):
        replies = [call("get_current_datetime") if i % 2 == 0 else call("get_battery_status") for i in range(8)]
        with mock.patch("orchestrator.execute_tool", return_value="x"):
            c, reply, seen = self.run_agent("ask", replies + [TEXT("end")])
        self.assertEqual(len(seen), 7)  # max_rounds 6 tool rounds plus the tool-free final one
        self.assertIsNone(seen[-1]["tools"])  # the final round is tool-free

    def test_saved_task_is_handed_to_the_next_similar_request(self):
        self.run_agent("yolo", [call("create_tool", spec()), call("miles_to_km", {"miles": 10}), TEXT("16.09 km")])
        c = self.convo()
        c.config["agent"]["mode"] = "yolo"
        seen = []
        with mock.patch("orchestrator.ask_gemini", side_effect=scripted([TEXT("25 miles is 40.23 km")], seen)):
            c.handle_command("/model gemini")
            c.send("please turn 25 miles into km")
        self.assertIn("A similar request was solved before", seen[0]["history"][-1])
        self.assertIn("miles_to_km", seen[0]["history"][-1])
        c.config["agent"]["mode"] = "off"
        seen.clear()
        with mock.patch("orchestrator.ask_gemini", side_effect=scripted([TEXT("fine")], seen)):
            c.send("please turn 30 miles into km")
        self.assertNotIn("similar request", seen[0]["history"][-1])

    def test_simple_single_tool_turns_are_not_saved(self):
        with mock.patch("orchestrator.execute_tool", return_value="80% battery"):
            self.run_agent("ask", [call("get_battery_status"), TEXT("80%")], prompt="how is the battery looking")
        self.assertEqual(agent.STORE.tasks, [])

    def test_commands(self):
        c = self.convo()
        self.assertIn("Agent mode: ask", c.handle_command("/agent"))
        self.assertIn("Agent mode: yolo", c.handle_command("/agent yolo"))
        self.assertEqual(c.config["agent"]["mode"], "yolo")
        self.assertEqual(c.handle_command("/agent nope"), "Usage: /agent off|ask|yolo")
        self.assertIn("Higher-level tools: off", c.handle_command("/elevated"))
        self.assertIn("Higher-level tools: on", c.handle_command("/elevated on"))
        self.assertTrue(agent.POLICY["high_tools"])
        c.handle_command("/elevated off")
        self.assertFalse(agent.POLICY["high_tools"])
        self.assertEqual(c.handle_command("/elevated maybe"), "Usage: /elevated on|off")
        self.assertEqual(c.handle_command("/tools"), "No saved tools")
        self.assertEqual(c.handle_command("/tasks"), "No saved tasks")
        info, meta, code = agent.prepare_tool(spec(), False)
        agent.STORE.add_tool(meta, code)
        agent.STORE.save_task("convert miles to km", [{"tool": "miles_to_km", "args": {}, "ok": True}], "r")
        self.assertIn("miles_to_km [normal] Convert miles", c.handle_command("/tools"))
        task_id = agent.STORE.tasks[0]["id"]
        self.assertIn(task_id, c.handle_command("/tasks"))
        self.assertEqual(c.handle_command(f"/tasks remove {task_id}"), f"Removed {task_id}")
        self.assertEqual(c.handle_command("/tasks remove zzz"), "No task zzz")
        c._rebuild_system()
        self.assertIn("miles_to_km", c.messages[0]["content"])
        self.assertEqual(c.handle_command("/tools remove miles_to_km"), "Removed miles_to_km")
        self.assertNotIn("miles_to_km", c.messages[0]["content"])
        agent.STORE.add_tool(meta, code)
        self.assertEqual(c.handle_command("/tools clear"), "Removed 1 tool")
        self.assertEqual(c.handle_command("/tasks clear"), "Removed 0 tasks")
        self.assertEqual(c.handle_command("/tools nope"), "Usage: /tools [list|remove <name>|clear]")
        for name in ("agent", "elevated", "tools", "tasks"):
            self.assertIn(name, [x["name"] for x in c.commands()])

    def test_settings(self):
        self.assertEqual(SETTINGS["agent.mode"], (str, ("off", "ask", "yolo")))
        c = self.convo()
        self.assertEqual((c.config["agent"]["mode"], c.config["agent"]["high_tools"]), ("ask", False))
        with self.assertRaises(ValueError):
            c.change_setting("agent.mode", "reckless")
        c.change_setting("agent.high_tools", "true")
        self.assertTrue(agent.POLICY["high_tools"])
        keys = {r["key"] for r in c.settings_list()}
        self.assertTrue({"agent.mode", "agent.high_tools", "agent.max_rounds"} <= keys)


class TestAgentServer(AgentCase, WebBase):
    def setUp(self):
        AgentCase.setUp(self)
        WebBase.setUp(self)

    def tearDown(self):
        WebBase.tearDown(self)

    def wait_pending(self):
        for _ in range(100):
            pending = self.httpd.pending_approvals()
            if pending:
                return pending[0]
            time.sleep(0.02)
        self.fail("no approval was requested")

    def ask_in_thread(self, **kw):
        out = {}
        t = threading.Thread(target=lambda: out.update(result=self.httpd.request_approval({"name": "t", "level": "normal", "code": "c"}, **kw)))
        t.start()
        return t, out

    def test_wiring_status_and_agent_endpoint(self):
        self.assertEqual(self.convo.approver, self.httpd.request_approval)
        code, status = self.jget("/api/status")
        self.assertEqual({k: status["agent"][k] for k in ("mode", "high_tools", "tools", "tasks")}, {"mode": "ask", "high_tools": False, "tools": 0, "tasks": 0})
        self.assertIn("rev", status["agent"])
        info, meta, src = agent.prepare_tool(spec(), False)
        agent.STORE.add_tool(meta, src)
        agent.STORE.save_task("convert miles to km", [{"tool": "miles_to_km", "args": {}, "ok": True}], "r")
        code, data = self.jget("/api/agent")
        self.assertEqual((code, data["mode"], data["high_tools"], data["pending"]), (200, "ask", False, []))
        self.assertEqual(set(data["modes"]), {"off", "ask", "yolo"})
        self.assertEqual(data["tools"][0]["name"], "miles_to_km")
        self.assertNotIn("code", data["tools"][0])
        self.assertEqual(data["tasks"][0]["tools"], ["miles_to_km"])
        self.assertEqual(self.request("GET", "/api/agent", token=None)[0].status, 401)

    def test_approval_round_trip(self):
        q = self.httpd.subscribe()
        t, out = self.ask_in_thread()
        pending = self.wait_pending()
        event, payload = q.get(timeout=2)
        self.assertEqual((event, payload["id"], payload["name"]), ("approval", pending["id"], "t"))
        self.assertEqual(self.jget("/api/agent")[1]["pending"][0]["id"], pending["id"])
        code, _ = self.jpost("/api/approve", {"id": pending["id"], "approve": True})
        t.join(3)
        self.assertEqual((code, out["result"]), (200, True))
        self.assertEqual(q.get(timeout=2)[0], "approval_done")
        self.assertEqual(self.jpost("/api/approve", {"id": pending["id"], "approve": True})[0], 404)
        t, out = self.ask_in_thread()
        pending = self.wait_pending()
        self.jpost("/api/approve", {"id": pending["id"], "approve": False})
        t.join(3)
        self.assertFalse(out["result"])
        self.assertEqual(self.request("POST", "/api/approve", {"id": "x", "approve": True}, token=None)[0].status, 401)

    def test_stop_and_timeout_deny(self):
        t, out = self.ask_in_thread()
        self.wait_pending()
        self.convo.cancel()
        t.join(3)
        self.assertFalse(out["result"])
        self.convo._cancel.clear()
        t, out = self.ask_in_thread(timeout=0.3)
        t.join(3)
        self.assertFalse(out["result"])
        self.assertEqual(self.httpd.pending_approvals(), [])

    def test_speak_passes_real_audio_through_with_its_type(self):
        with mock.patch("tts.synthesize_audio", return_value=(b"ID3xxxx", "audio/mpeg")):
            resp, data = self.request("POST", "/api/speak", {"text": "hello"})
        self.assertEqual((resp.status, resp.getheader("Content-Type"), data), (200, "audio/mpeg", b"ID3xxxx"))


class TestAudioFixes(unittest.TestCase):
    def test_package_audio(self):
        wav = b"RIFF\x00\x00\x00\x00WAVEfmt "
        self.assertEqual(tts.package_audio(wav, "audio/L16;rate=24000"), (wav, "audio/wav"))  # no double header
        self.assertEqual(tts.package_audio(b"ID3\x04" + b"x" * 20, "")[1], "audio/mpeg")
        self.assertEqual(tts.package_audio(b"\xff\xfb\x90\x00" + b"x" * 20, "")[1], "audio/mpeg")
        self.assertEqual(tts.package_audio(b"OggS" + b"x" * 20, "")[1], "audio/ogg")
        self.assertEqual(tts.package_audio(b"fLaC" + b"x" * 20, "")[1], "audio/flac")
        self.assertEqual(tts.package_audio(b"\x00\x00\x00\x18ftypmp42" + b"x" * 8, "")[1], "audio/mp4")
        self.assertEqual(tts.package_audio(b"x" * 20, "audio/webm;codecs=opus"), (b"x" * 20, "audio/webm"))
        pcm = b"\x01\x00" * 50
        out, kind = tts.package_audio(pcm, "audio/L16;codec=pcm;rate=16000")
        self.assertEqual((kind, out[:4], int.from_bytes(out[24:28], "little"), out[44:]), ("audio/wav", b"RIFF", 16000, pcm))
        self.assertEqual(int.from_bytes(tts.package_audio(pcm, "")[0][24:28], "little"), 24000)

    def test_synthesize_audio_uses_the_container_the_model_sent(self):
        import base64
        reply = type("R", (), {"status_code": 200, "json": lambda self: {"candidates": [{"content": {"parts": [
            {"inlineData": {"mimeType": "audio/mpeg", "data": base64.b64encode(b"ID3" + b"x" * 30).decode()}}]}}]}})()
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"}):
            audio, kind = tts.synthesize_audio("hi there", "Kore", "m", post=lambda *a, **k: reply)
        self.assertEqual((kind, audio[:3]), ("audio/mpeg", b"ID3"))

    def test_mic_source_is_never_a_monitor(self):
        listing = ("1\tOpenSL_ES_sink.monitor\tmodule-sles-sink.c\ts16le 2ch 44100Hz\tSUSPENDED\n"
                   "2\tOpenSL_ES_source\tmodule-sles-source.c\ts16le 2ch 44100Hz\tSUSPENDED\n"
                   "5\tOpenSL_ES_source.4\tmodule-sles-source.c\ts16le 2ch 44100Hz\tIDLE\n")
        self.assertEqual(pa_record.choose_source(listing), "OpenSL_ES_source")
        self.assertEqual(pa_record.choose_source("1\tother_mic\tm\tfmt\tIDLE\n2\tx.monitor\tm\tfmt\tIDLE"), "other_mic")
        self.assertIsNone(pa_record.choose_source("1\tOpenSL_ES_sink.monitor\tm\tfmt\tIDLE"))
        self.assertIsNone(pa_record.choose_source(""))
        with mock.patch("subprocess.run", side_effect=OSError):
            self.assertIsNone(pa_record.detect_source())


class TestPickerHelpers(unittest.TestCase):
    def test_stay_options(self):
        res = node("console.log(JSON.stringify({ o: a.STAY_OPTIONS, labels: a.STAY_OPTIONS.map((x) => a.stayLabel(x[0])) }));")
        self.assertEqual(res["o"][0], [0, "Until I turn it off"])
        self.assertEqual([n for n, _ in res["o"]], [0, 1, 3, 5, 10, 20])
        self.assertEqual(res["labels"][:2], ["until I turn it off", "1 message"])


@unittest.skipUnless(HAVE_JSDOM, "jsdom not installed")
class TestBrowserAgentUi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.res = run_smoke()

    def test_runs_clean(self):
        self.assertNotIn("crash", self.res, self.res.get("crash"))
        self.assertEqual(self.res["errors"], [])

    def test_model_picker_has_no_slider_and_morphs_tick_into_dropdown(self):
        self.assertEqual({k: self.res["modelSheet"][k] for k in ("items", "controls", "slider")}, {"items": 4, "controls": 3, "slider": False})
        p = self.res["picker"]
        self.assertFalse(p["autoHasControl"])
        self.assertEqual((p["arrow"], p["activeBefore"]), ("\u25be", False))
        self.assertEqual(p["chose"], ["/model qwen"])  # tapping the name alone = until turned off
        self.assertEqual((p["tick"], p["activeAfter"], p["rowOn"]), ("\u2713", True, True))
        self.assertTrue(p["opened"])
        self.assertEqual(p["options"][0], "Until I turn it off")
        self.assertEqual((p["counted"], p["remaining"], p["closed"], p["backToAuto"]), (1, "5", True, "auto"))

    def test_agent_tab(self):
        a = self.res["agent"]
        self.assertEqual((a["panel"], a["tools"], a["tasks"], a["mode"], a["help"], a["toolTitle"], a["elevated"]),
                         (["agent"], 1, 1, ["ask"], "asks first", "miles_to_km", False))
        self.assertTrue(a["yoloSaved"] and a["elevatedSaved"])
        self.assertEqual(a["commands"], [True, True, True, True])

    def test_approval_card(self):
        ap = self.res["approval"]
        self.assertTrue(ap["open"] and ap["warn"] and ap["code"])
        self.assertEqual((ap["name"], ap["level"], ap["desc"], ap["how"]), ("miles_to_km", "higher-level", "Convert miles to km.", "Multiplies by 1.609."))
        self.assertEqual(ap["reasons"], "Higher-level because it imports subprocess")
        self.assertEqual((ap["allowed"], ap["closed"]), ({"id": "ap1", "approve": True}, True))
        self.assertEqual(ap["denied"], {"id": "ap2", "approve": False})
        self.assertEqual(ap["yolo"], {"approve": {"id": "ap3", "approve": True}, "mode": True})
        self.assertTrue(ap["autoClosed"])


if __name__ == "__main__":
    unittest.main()
