"""
Longarm agent: lets the Longarm Android app reach a Linux machine.

    python3 -m longarm_agent                 run the agent
    python3 -m longarm_agent --pair          print the pairing code to paste into the app
    python3 -m longarm_agent --approve CODE  allow the phone showing this code
    python3 -m longarm_agent --deny CODE     refuse it

Settings are in ~/.config/longarm/agent.json, created on the first run with a new token:
    disks          mount points to report; a drive that should be allowed to sleep must not be listed
    containers     true to list Docker containers and allow restarting them
    cards_command  a command that prints extra read-only cards as JSON (see the README)
    ai_engines     local AI Docker Compose stacks to switch between (see the README)
    listen         addresses to listen on; empty means Tailscale and the home network
    role           "computer" or "server"; servers have no sleep or lock, and start with shutdown turned off
    allow_restart, allow_shutdown   whether the app may restart or power off this machine
"""
import json
import logging
import os
import pathlib
import secrets
import shutil
import subprocess
import sys
import threading
import time

from .desktop import AIEngines
from .phone_api import PhoneAPI, certificate_fingerprint, ensure_certificate, format_pairing_code, wanted_addresses

CONFIG_DIR = pathlib.Path(os.environ.get("XDG_CONFIG_HOME", pathlib.Path.home() / ".config")) / "longarm"
CONFIG = CONFIG_DIR / "agent.json"
DEFAULTS = {"enabled": True, "listen": "", "port": 47126, "token": "", "disks": ["/"], "containers": False,
            "cards_command": [], "ai_engines": []}
TEMPERATURE_SENSORS = ("coretemp", "k10temp", "zenpower", "cpu_thermal")
CONTAINER_CACHE_S = 5
CARDS_CACHE_S = 60
RESTART_TIMEOUT_S = 180
ANSWER_PREFIX = "pair-answer-"  # --approve and --deny leave a file with this prefix for the running agent


def save_config(config):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    with os.fdopen(os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        json.dump(config, f, indent=1)


def load_config():
    """The saved settings over the defaults. Writes the file, private to this user, when it needs a new token."""
    try:
        config = {**DEFAULTS, **json.loads(CONFIG.read_text())}
    except (OSError, ValueError):
        config = dict(DEFAULTS)
    if not config["token"]:
        config["token"] = secrets.token_urlsafe(24)
        save_config(config)
    return config


class SystemStats:
    def __init__(self, disks):
        self.disks = disks
        self.lock = threading.Lock()
        self.last_cpu = self._cpu_times()

    @staticmethod
    def _cpu_times():
        with open("/proc/stat") as f:
            values = [int(v) for v in f.readline().split()[1:]]
        return sum(values), values[3] + values[4]  # total, idle + iowait

    def _cpu_percent(self):
        """Use since the previous reading, so it needs no waiting."""
        with self.lock:
            total, idle = self._cpu_times()
            spent, rested = total - self.last_cpu[0], idle - self.last_cpu[1]
            self.last_cpu = (total, idle)
        return round(100 * (spent - rested) / spent) if spent > 0 else 0

    @staticmethod
    def _temperature():
        for sensor in pathlib.Path("/sys/class/hwmon").glob("hwmon*"):
            try:
                if (sensor / "name").read_text().strip() in TEMPERATURE_SENSORS:
                    return round(int((sensor / "temp1_input").read_text()) / 1000)
            except (OSError, ValueError):
                continue
        return None

    @staticmethod
    def _memory_percent():
        info = {}
        with open("/proc/meminfo") as f:
            for line in f:
                key, value = line.split(":", 1)
                info[key] = int(value.split()[0])
        return round(100 * (info["MemTotal"] - info["MemAvailable"]) / info["MemTotal"])

    def _disks(self):
        found = []
        for path in self.disks:
            try:
                usage = shutil.disk_usage(path)
            except OSError:
                continue
            found.append({"path": path, "percent": round(100 * usage.used / usage.total)})
        return found

    def snapshot(self):
        with open("/proc/uptime") as f:
            uptime = int(float(f.read().split()[0]))
        return {"cpu_percent": self._cpu_percent(), "temperature": self._temperature(),
                "memory_percent": self._memory_percent(), "uptime_s": uptime, "disks": self._disks()}


class Containers:
    """Docker containers, listed through the CLI and cached briefly because the app polls every few seconds."""

    def __init__(self):
        self.lock = threading.Lock()
        self.cached = []
        self.cached_at = 0.0
        self.restarting = set()

    def _read(self):
        result = subprocess.run(["docker", "ps", "-a", "--format", "{{json .}}"], capture_output=True, text=True,
                                timeout=20)
        if result.returncode != 0:
            return []
        rows = (json.loads(line) for line in result.stdout.splitlines() if line.strip())
        return sorted(({"name": r["Names"], "running": r["State"] == "running", "status": r["Status"]} for r in rows),
                      key=lambda c: c["name"].lower())

    def list(self):
        with self.lock:
            if time.monotonic() - self.cached_at > CONTAINER_CACHE_S:
                try:
                    self.cached = self._read()
                except (OSError, ValueError, subprocess.SubprocessError) as e:
                    logging.warning("Could not list containers: %s", e)
                self.cached_at = time.monotonic()
            restarting = set(self.restarting)
            return [{**c, "restarting": c["name"] in restarting} for c in self.cached]

    def _restart(self, name):
        try:
            subprocess.run(["docker", "restart", name], capture_output=True, timeout=RESTART_TIMEOUT_S)
        except (OSError, subprocess.SubprocessError) as e:
            logging.warning("Could not restart %s: %s", name, e)
        finally:
            with self.lock:
                self.restarting.discard(name)
                self.cached_at = 0.0

    def restart_route(self, body):
        """Only a name from the current list is accepted. The restart continues after the reply."""
        name = body.get("name")
        if not any(c["name"] == name for c in self.list()):
            return 404, {"error": "no such container"}
        with self.lock:
            if name in self.restarting:
                return 409, {"error": "already restarting"}
            self.restarting.add(name)
        threading.Thread(target=self._restart, args=(name,), daemon=True).start()
        return 202, {"restarting": name}


class Cards:
    """Extra read-only cards from a configured command, refreshed in the background so a slow command can't delay."""

    def __init__(self, command):
        self.command = command
        self.lock = threading.Lock()
        self.cached = []
        self.cached_at = 0.0
        self.running = False

    def _refresh(self):
        try:
            result = subprocess.run(self.command, capture_output=True, text=True, timeout=120)
            cards = json.loads(result.stdout) if result.returncode == 0 else None
            if isinstance(cards, list):
                self.cached = cards
        except (OSError, ValueError, subprocess.SubprocessError) as e:
            logging.warning("Could not read the cards command: %s", e)
        finally:
            with self.lock:
                self.running = False
                self.cached_at = time.monotonic()

    def get(self):
        with self.lock:
            if not self.running and (not self.cached_at or time.monotonic() - self.cached_at > CARDS_CACHE_S):
                self.running = True
                threading.Thread(target=self._refresh, daemon=True).start()
        return self.cached


def answer_from_command_line(flag, code):
    """Hands the answer to the running agent through a private file, which it removes after reading."""
    if not code.isdigit() or len(code) != 6:
        sys.exit("Expected the six-digit code shown on the phone")
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    answer = CONFIG_DIR / f"{ANSWER_PREFIX}{'allow' if flag == '--approve' else 'deny'}-{code}"
    os.close(os.open(answer, os.O_WRONLY | os.O_CREAT, 0o600))
    print("Sent to the running agent. The phone updates in a moment.")


def apply_answers(api):
    for answer in CONFIG_DIR.glob(f"{ANSWER_PREFIX}*"):
        _, _, _, verdict, code = answer.name.split("-")
        answer.unlink(missing_ok=True)
        logging.info("Pairing %s: %s", code, verdict if api.answer_pairing(code, verdict == "allow") else "no match")


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    for flag in ("--approve", "--deny"):
        if flag in sys.argv:
            index = sys.argv.index(flag)
            answer_from_command_line(flag, sys.argv[index + 1] if index + 1 < len(sys.argv) else "")
            return
    config = load_config()
    if "--pair" in sys.argv:
        cert, _ = ensure_certificate(str(CONFIG_DIR))
        print(format_pairing_code(config, wanted_addresses(config), certificate_fingerprint(cert)))
        return
    stats = SystemStats(config["disks"])
    containers = Containers() if config["containers"] else None
    cards = Cards(config["cards_command"]) if config["cards_command"] else None

    def state():
        snapshot = {"system": stats.snapshot()}
        if containers:
            snapshot["containers"] = containers.list()
        if cards:
            snapshot["cards"] = cards.get()
        return snapshot

    ai = AIEngines(config["ai_engines"]) if config["ai_engines"] else None
    routes = {"/containers/restart": containers.restart_route} if containers else {}
    if ai:
        routes["/ai"] = ai.route

    def pair_requested(phone, code):
        logging.info("%s asks to connect. Check its code is %s, then run: python3 -m longarm_agent --approve %s",
                     phone, code, code)

    extra = (lambda: {"ai": ai.state()}) if ai else None
    api = PhoneAPI(config, state, None, str(CONFIG_DIR), routes, pair_requested, extra, lambda: save_config(config))
    logging.info("Longarm agent: %s on %s", api.status, ", ".join(api.addresses) or "no address yet")
    while True:
        time.sleep(1)
        apply_answers(api)


if __name__ == "__main__":
    main()
