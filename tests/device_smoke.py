#!/usr/bin/env python3
"""Check the installed APK against a rooted Android device."""

import argparse
import json
import re
import shlex
import subprocess
import time


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--exercise", action="store_true",
                    help="Exercise both roles, Bluetooth power, boot restore, and configuration restore.")
args = parser.parse_args()


def adb(*command):
    result = subprocess.run(["adb", "-s", args.serial, *command], text=True,
                            capture_output=True, timeout=125)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def root(script):
    return adb("shell", "su -c " + shlex.quote(script))


apk = adb("shell", "pm", "path", "local.bluetooth.speaker").splitlines()[0].removeprefix("package:")


def call(command, argument=None):
    started = time.monotonic()
    words = ["app_process", "/system/bin", "local.bluetooth.speaker.RootCommand", command]
    if argument is not None:
        words.append(argument)
    output = root("CLASSPATH=" + shlex.quote(apk) + " " + shlex.join(words))
    result = next(json.loads(line) for line in reversed(output.splitlines()) if line.startswith("{"))
    assert "error" not in result, result
    summary = dict(result)
    if "paired" in summary:
        summary["pairedCount"] = len(summary.pop("paired"))
    print(json.dumps({"command": command, "argument": argument,
                      "elapsedMs": round((time.monotonic() - started) * 1000), "result": summary},
                     ensure_ascii=False), flush=True)
    return result


def snapshot():
    return root("getprop | grep -E '^\\[(bluetooth.profile.(a2dp|avrcp)\\.|persist.vendor.service.bt.a2dp.sink)'"
                "; settings get global bluetooth_on"
                "; getprop persist.vendor.bt.a2dp.sink_conn"
                "; cmd overlay list --user 0 com.android.bluetooth"
                "; settings get global adb_enabled"
                "; getprop service.adb.tcp.port")


before = snapshot()
diagnosis = call("diagnose")
assert diagnosis["canControl"], diagnosis
assert diagnosis["runtimeControllerReady"], diagnosis
assert snapshot() == before, "Diagnostics changed device configuration"
initial = call("status")
assert initial["controllerReady"]
if not args.exercise:
    print("PASS: read-only diagnostics and status", flush=True)
    raise SystemExit(0)

assert initial["backend"] in ("aosp-root-apk", "resource-root-apk"), "Exercise requires a standalone backend"
saved = json.loads(root("if [ -f /data/user_de/0/local.bluetooth.speaker/files/root-control/settings.json ]; then "
                        "cat /data/user_de/0/local.bluetooth.speaker/files/root-control/settings.json; else echo '{}'; fi"))


def check(mode, enabled):
    status = call("status")
    assert status["mode"] == mode, status
    assert status["enabled"] == enabled, status
    if status["profileBindingRequired"]:
        profiles = root("dumpsys bluetooth_manager | grep '^Profile:' || true")
        expected = "Profile: A2dpSinkService" if mode == "sink" else "Profile: A2dpService"
        other = "Profile: A2dpService" if mode == "sink" else "Profile: A2dpSinkService"
        assert (expected in profiles.splitlines()) == enabled, profiles
        assert other not in profiles.splitlines(), profiles
    else:
        assert status["profileReady"] == enabled, status
    if enabled:
        expected_uuid = "0000110b" if mode == "sink" else "0000110a"
        assert any(uuid.startswith(expected_uuid) for uuid in status["localUuids"]), status
    return status


def enable_log():
    dump = root("dumpsys bluetooth_manager")
    return dump.split("Enable log:", 1)[1].split("Bluetooth crashed", 1)[0]


try:
    call("enable")
    unchanged = enable_log()
    call("enable")
    assert enable_log() == unchanged, "Repeated enable interrupted Bluetooth"
    for mode in ("source", "sink"):
        call("mode", mode)
        check(mode, True)
    call("disable")
    for mode in ("source", "sink"):
        call("mode", mode)
        check(mode, False)
        call("enable")
        check(mode, True)
        call("disable")
    call("enable")
    call("default-mode", "source")
    boot = call("apply-boot")
    if not boot.get("alreadyApplied"):
        check("source", True)
    assert call("apply-boot").get("alreadyApplied"), "Boot restore is not idempotent"
    original = saved.get("originalProperties")
    if original is None:
        original = json.loads(root("cat /data/user_de/0/local.bluetooth.speaker/files/root-control/settings.json"))["originalProperties"]
    sink_flag = ("bluetooth.profile.a2dp.sink.enabled"
                 if initial["configurationStrategy"] == "profile-properties" else "persist.vendor.service.bt.a2dp.sink")
    original_mode = "sink" if original.get(sink_flag) == "true" else "source"
    call("restore")
    restored = check(original_mode, True)
    assert not restored["restoreOnBoot"], restored
    unchanged = enable_log()
    call("restore")
    assert not check(original_mode, True)["restoreOnBoot"]
    assert enable_log() == unchanged, "Repeated restore interrupted Bluetooth"
    properties = dict(re.findall(r"^\[([^]]+)\]: \[(.*)\]$", root("getprop"), re.MULTILINE))
    for name, value in original.items():
        assert properties.get(name, "") == value, (name, properties.get(name), value)
    print("PASS: role switching, off-state preservation, boot restore, and original configuration restore",
          flush=True)
finally:
    call("mode", initial["mode"])
    call("default-mode", initial["defaultMode"])
    if not initial["restoreOnBoot"]:
        call("restore")
    if not initial["enabled"]:
        call("disable")
    final = check(initial["mode"], initial["enabled"])
    for device in initial["connected"]:
        if initial["enabled"]:
            call("connect", device["address"])
