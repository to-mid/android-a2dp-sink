#!/usr/bin/env python3
"""Exercise the shipped startup script with a temporary Android command environment."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "app/assets/boot-restore.sh"


class BootHookTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.state = self.root / "state"
        self.state.mkdir()
        (self.state / "settings.json").write_text('{"restoreOnBoot":true}')
        self.apk = self.root / "updated-base.apk"
        self.apk.touch()
        self.env = dict(os.environ, PATH=str(self.bin) + ":" + os.environ["PATH"],
                        FIXTURE_ROOT=str(self.root), FIXTURE_APK=str(self.apk),
                        FIXTURE_BOOT="1", FIXTURE_FAILURES="0")
        self.command("getprop", 'printf "%s" "$FIXTURE_BOOT"')
        self.command("pm", 'printf "package:%s\\n" "$FIXTURE_APK"')
        self.command("sleep", "exit 0")
        self.command("app_process", '''
count=0
[ ! -f "$FIXTURE_ROOT/count" ] || count=$(cat "$FIXTURE_ROOT/count")
count=$((count + 1))
echo "$count" > "$FIXTURE_ROOT/count"
printf '%s' "$CLASSPATH" > "$FIXTURE_ROOT/classpath"
[ "$3" = apply-boot ] || exit 99
if [ "$count" -le "$FIXTURE_FAILURES" ]; then
    echo '{"error":"Bluetooth not ready"}'
    exit 1
fi
echo '{"ok":true}'
''')
        self.script = self.root / "boot.sh"
        # Only filesystem dependencies change; execute the shipped control flow.
        source = SCRIPT.read_text().replace(
            "STATE=/data/user_de/0/local.bluetooth.speaker/files/root-control",
            "STATE='" + str(self.state) + "'").replace(
            "/system/bin/app_process", str(self.bin / "app_process"))
        self.script.write_text(source)

    def command(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o700)

    def run_hook(self):
        return subprocess.run(["sh", str(self.script)], env=self.env, capture_output=True, timeout=5)

    def count(self):
        path = self.root / "count"
        return int(path.read_text()) if path.exists() else 0

    def test_success_stops_after_one_attempt_and_uses_current_apk(self):
        self.assertEqual(self.run_hook().returncode, 0)
        self.assertEqual(self.count(), 1)
        self.assertEqual((self.root / "classpath").read_text(), str(self.apk))

    def test_transient_failure_retries_then_stops(self):
        self.env["FIXTURE_FAILURES"] = "1"
        self.assertEqual(self.run_hook().returncode, 0)
        self.assertEqual(self.count(), 2)
        self.assertIn('"ok":true', (self.state / "boot.log").read_text())

    def test_permanent_failure_is_bounded_and_logged(self):
        self.env["FIXTURE_FAILURES"] = "99"
        self.assertEqual(self.run_hook().returncode, 1)
        self.assertEqual(self.count(), 3)
        self.assertIn('"error"', (self.state / "boot.log").read_text())

    def test_boot_not_ready_does_not_change_bluetooth(self):
        self.env["FIXTURE_BOOT"] = "0"
        self.assertEqual(self.run_hook().returncode, 0)
        self.assertEqual(self.count(), 0)

    def test_cleared_app_or_uninstalled_apk_does_not_run(self):
        (self.state / "settings.json").unlink()
        self.assertEqual(self.run_hook().returncode, 0)
        self.assertEqual(self.count(), 0)
        (self.state / "settings.json").touch()
        self.apk.unlink()
        self.assertEqual(self.run_hook().returncode, 0)
        self.assertEqual(self.count(), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
