"""Desktop controls for the Longarm app: brightness through Plasma, volume through PipeWire or PulseAudio."""
import json
import logging
import os
import platform
import re
import socket
import subprocess
import threading

log = logging.getLogger(__name__)

BRIGHTNESS_SERVICE = "org.kde.ScreenBrightness"
BRIGHTNESS_PATH = "/org/kde/ScreenBrightness"
DISPLAY_INTERFACE = "org.kde.ScreenBrightness.Display"
SUPPRESS_OSD = 1  # SetBrightness flag: no on-screen indicator on the laptop for a change made from the phone
SINK = "@DEFAULT_SINK@"


def _run(*cmd):
    """Stdout of a command, or None if it failed or isn't installed."""
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired) as e:
        log.warning("%s failed: %s", cmd[0], e)
        return None
    return result.stdout if result.returncode == 0 else None


def _properties(path, interface, *names):
    """Values of D-Bus properties on the brightness service, or None if it can't be read."""
    out = _run("busctl", "--user", "--json=short", "get-property", BRIGHTNESS_SERVICE, path, interface, *names)
    if out is None:
        return None
    values = [json.loads(line)["data"] for line in out.splitlines() if line.strip()]
    return values if len(values) == len(names) else None


def screens():
    """Every screen Plasma can dim: [{"id", "label", "internal", "percent"}]. Empty outside Plasma."""
    names = _properties(BRIGHTNESS_PATH, BRIGHTNESS_SERVICE, "DisplaysDBusNames")
    found = []
    for name in names[0] if names else []:
        values = _properties(f"{BRIGHTNESS_PATH}/{name}", DISPLAY_INTERFACE,
                             "Label", "IsInternal", "Brightness", "MaxBrightness")
        if values and values[3] > 0:
            found.append({"id": name, "label": values[0], "internal": values[1],
                          "percent": round(values[2] * 100 / values[3])})
    return found


def set_brightness(screen, percent):
    """True if the brightness of a screen named by screens() was set."""
    if not re.fullmatch(r"display\d+", screen):
        return False
    path = f"{BRIGHTNESS_PATH}/{screen}"
    maximum = _properties(path, DISPLAY_INTERFACE, "MaxBrightness")
    if not maximum:
        return False
    value = round(max(0, min(100, percent)) * maximum[0] / 100)
    return _run("busctl", "--user", "call", BRIGHTNESS_SERVICE, path, DISPLAY_INTERFACE, "SetBrightness", "iu",
                str(value), str(SUPPRESS_OSD)) is not None


def volume():
    """The default output as {"percent", "muted"}, or None if it can't be read."""
    level = _run("pactl", "get-sink-volume", SINK)
    muted = _run("pactl", "get-sink-mute", SINK)
    match = re.search(r"(\d+)%", level or "")
    if not match or muted is None:
        return None
    return {"percent": int(match.group(1)), "muted": "yes" in muted}


def set_volume(percent=None, muted=None):
    """Change the default output's level (0-100), its mute state, or both. True on success."""
    ok = True
    if muted is not None:
        ok = _run("pactl", "set-sink-mute", SINK, "1" if muted else "0") is not None
    if percent is not None:
        ok = _run("pactl", "set-sink-volume", SINK, f"{max(0, min(100, percent))}%") is not None and ok
    return ok


def lan_address():
    """The address this machine uses on its local network, or "" if there is none."""
    out = _run("ip", "-j", "route", "get", "1.1.1.1")
    try:
        return json.loads(out or "[]")[0].get("prefsrc", "")
    except (ValueError, IndexError):
        return ""


def _read(path):
    try:
        with open(path, errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def _cpu_description():
    info = _read("/proc/cpuinfo")
    model = re.search(r"model name\s*:\s*(.+)", info)
    physical = {tuple(pair) for pair in re.findall(r"physical id\s*:\s*(\d+).*?core id\s*:\s*(\d+)", info, re.S)}
    threads = os.cpu_count() or 0
    cores = f"{len(physical)} cores, " if physical else ""
    return f"{model.group(1) if model else platform.machine()} ({cores}{threads} threads)"


def _memory_description():
    match = re.search(r"MemTotal:\s*(\d+)", _read("/proc/meminfo"))
    return f"{int(match.group(1)) / 1048576:.1f} GB" if match else ""


def _graphics():
    out = _run("lspci") or ""
    return [line.split(": ", 1)[1] for line in out.splitlines()
            if re.search(r"VGA|3D controller", line) and ": " in line]


def _storage():
    out = _run("lsblk", "-J", "-d", "-o", "NAME,SIZE,MODEL,TYPE") or "{}"
    devices = json.loads(out).get("blockdevices", [])
    return [f"{d.get('model') or d['name']} ({d.get('size', '?')})" for d in devices if d.get("type") == "disk"
            and not d["name"].startswith(("zram", "loop"))]


def specs():
    """Label and value rows describing this machine. Empty values are left out."""
    model = " ".join(filter(None, (_read("/sys/class/dmi/id/sys_vendor"), _read("/sys/class/dmi/id/product_version"),
                                   _read("/sys/class/dmi/id/product_name"))))
    system = re.search(r'PRETTY_NAME="?([^"\n]+)', _read("/etc/os-release"))
    rows = [("Name", socket.gethostname()), ("Model", model),
            ("System", system.group(1) if system else platform.system()), ("Kernel", platform.release()),
            ("Processor", _cpu_description()), ("Memory", _memory_description())]
    rows += [("Graphics", gpu) for gpu in _graphics()]
    rows += [("Storage", disk) for disk in _storage()]
    rows += [("Tailscale", tailscale_address()), ("Local network", lan_address())]
    return [{"label": label, "value": value} for label, value in rows if value]


class AIEngines:
    """
    Local AI engines run as Docker Compose stacks, at most one at a time, since they share a graphics card.
    Each configured engine is {"id", "label", "container", "compose_dir"}.
    """

    def __init__(self, engines):
        self.engines = engines
        self.lock = threading.Lock()
        self.busy = False

    @staticmethod
    def _running(engine):
        return (_run("docker", "inspect", "-f", "{{.State.Running}}", engine["container"]) or "").strip() == "true"

    def state(self):
        active = next((engine["id"] for engine in self.engines if self._running(engine)), None)
        return {"active": active, "busy": self.busy,
                "engines": [{"id": engine["id"], "label": engine["label"]} for engine in self.engines]}

    @staticmethod
    def _compose(engine, *args):
        subprocess.run(["docker", "compose", *args], cwd=os.path.expanduser(engine["compose_dir"]),
                       capture_output=True, timeout=180, check=False)

    def _switch(self, wanted):
        try:
            for engine in self.engines:
                if engine["id"] != wanted:
                    self._compose(engine, "stop")
            for engine in self.engines:
                if engine["id"] == wanted:
                    self._compose(engine, "up", "-d")
        except (OSError, subprocess.SubprocessError) as e:
            log.warning("Could not switch the AI engine: %s", e)
        finally:
            with self.lock:
                self.busy = False

    def route(self, body):
        """POST /ai with {"engine": an id or "off"}. The switch continues after the reply."""
        wanted = body.get("engine")
        if wanted != "off" and not any(engine["id"] == wanted for engine in self.engines):
            return 400, {"error": "unknown engine"}
        with self.lock:
            if self.busy:
                return 409, {"error": "already switching"}
            self.busy = True
        threading.Thread(target=self._switch, args=(wanted,), daemon=True, name="ai-switch").start()
        return 202, {"switching": wanted}


def tailscale_peers():
    """(host name, address) of the other machines currently online on this Tailscale network."""
    out = _run("tailscale", "status", "--json")
    try:
        peers = json.loads(out or "{}").get("Peer", {}).values()
    except ValueError:
        return []
    return [(p.get("HostName", ""), p["TailscaleIPs"][0]) for p in peers
            if p.get("Online") and p.get("TailscaleIPs")]


def tailscale_address():
    """This machine's Tailscale IPv4 address, or "" if Tailscale isn't up."""
    out = _run("tailscale", "ip", "-4")
    return out.split()[0] if out and out.split() else ""
