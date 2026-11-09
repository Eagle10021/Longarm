"""
HTTPS API for Longarm, the Android app. It needs no Home Assistant, and listens on the Tailscale and home-network
addresses unless "listen" names others.

Connections use a self-signed certificate. The phone learns its fingerprint from the pairing code and refuses any
other certificate, so the token is never sent to a different machine.

Finding and pairing need no token. A phone broadcasts on UDP port 47127 to find machines on the local network. To
pair, it sends a nonce to /pair/request, and both sides derive a six-digit code from the nonce and the certificate
fingerprint each of them sees. The owner compares the codes and approves on this machine, so an impostor in the
middle would show a different code. Only then does /pair/poll return the token.

Every other request needs "Authorization: Bearer <token>".
    GET  /peers       other Longarm machines on this Tailscale network
    GET  /specs       label and value rows describing this machine
    GET  /state       the sections this machine has: keyboard, battery, power, system, containers, screens, volume
    POST /keyboard    any of: effect, color, colors (4), brightness (Low/High/Off), speed (1-4), profile
    POST /power       charge_mode (normal/conservation/rapid) and/or power_profile
    POST /brightness  {"screen": "display0", "percent": 50}
    POST /volume      {"percent": 40} and/or {"muted": true}
Further routes can be added with extra_routes.
"""
import concurrent.futures
import hashlib
import hmac
import http.client
import ipaddress
import json
import logging
import os
import re
import secrets
import socket
import ssl
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import desktop

log = logging.getLogger(__name__)

KEYBOARD_KEYS = ("effect", "color", "colors", "brightness", "speed", "profile")
POWER_KEYS = ("charge_mode", "power_profile")
RETRY_S = 30  # Tailscale and the network can come up after this program does
CONNECTION_TIMEOUT_S = 15
MAX_BODY_BYTES = 4096
DISCOVERY_PORT = 47127
DISCOVERY_REQUEST = b"longarm-discover-1"
PAIR_LIFETIME_S = 120
PAIR_COOLDOWN_S = 5  # a phone on the network can't raise approval prompts faster than this
PROBE_TIMEOUT_S = 1.5


def ensure_certificate(directory):
    """(certificate path, key path), creating a private self-signed pair on the first run."""
    cert, key = os.path.join(directory, "phone-cert.pem"), os.path.join(directory, "phone-key.pem")
    if not (os.path.exists(cert) and os.path.exists(key)):
        os.makedirs(directory, exist_ok=True)
        previous_umask = os.umask(0o077)
        try:
            subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                            "-days", "7300", "-subj", "/CN=Longarm", "-keyout", key, "-out", cert],
                           check=True, capture_output=True, timeout=30)
        finally:
            os.umask(previous_umask)
    return cert, key


def certificate_fingerprint(cert):
    """Lowercase SHA-256 of the certificate, the value the phone checks."""
    with open(cert) as f:
        return hashlib.sha256(ssl.PEM_cert_to_DER_cert(f.read())).hexdigest()


def pair_code(fingerprint, nonce):
    """The six digits both sides show. The phone computes them from the certificate it actually received."""
    digest = hashlib.sha256(f"{fingerprint}{nonce}".encode()).hexdigest()
    return f"{int(digest[:12], 16) % 1_000_000:06d}"


def format_pairing_code(config, addresses, fingerprint):
    port = config.get("port", 47126)
    query = urllib.parse.urlencode({
        "n": socket.gethostname(),
        "a": ",".join(f"{address}:{port}" for address in addresses),
        "t": config["token"],
        "f": fingerprint,
    })
    return f"longarm://pair?{query}"


def wanted_addresses(config):
    listen = config.get("listen")
    if listen:
        return [listen] if isinstance(listen, str) else list(listen)
    return [a for a in (desktop.tailscale_address(), desktop.lan_address()) if a]


def _probe(name, address, port):
    """This machine's public details if a Longarm device answers at address, otherwise None."""
    context = ssl.create_default_context()
    context.check_hostname = False  # only finding devices here; pairing verifies identity
    context.verify_mode = ssl.CERT_NONE
    connection = http.client.HTTPSConnection(address, port, timeout=PROBE_TIMEOUT_S, context=context)
    try:
        connection.request("GET", "/hello")
        hello = json.loads(connection.getresponse().read(MAX_BODY_BYTES))
        if hello.get("app") == "longarm":
            return {"name": hello.get("name") or name, "address": f"{address}:{port}",
                    "fingerprint": hello.get("fingerprint", "")}
    except (OSError, ValueError, http.client.HTTPException):
        pass
    finally:
        connection.close()
    return None


class PhoneAPI:
    def __init__(self, config, get_state, on_command, cert_dir, extra_routes=None, on_pair_request=None,
                 extra_state=None):
        """
        get_state() returns the latest snapshot. on_command(dict) must be safe to call from any thread, and is None
        on machines without keyboard and power controls. extra_routes maps a POST path to fn(body) -> (status, reply).
        on_pair_request(phone name, code) is called from a server thread when a phone asks to pair.
        extra_state() adds sections to /state, and is called on the request's own thread.
        """
        self.config = config
        self.get_state = get_state
        self.on_command = on_command
        self.cert_dir = cert_dir
        self.extra_routes = extra_routes or {}
        self.on_pair_request = on_pair_request
        self.extra_state = extra_state
        self._specs = None
        self.addresses = []
        self._pair_lock = threading.Lock()
        self._pair = None
        self._last_pair_at = -PAIR_COOLDOWN_S
        self._udp = None
        self.status = "Off"
        self._servers = {}
        self._context = None
        self._fingerprint = ""
        self._retry = None
        self._closed = False
        if config.get("enabled") and config.get("token"):
            self.status = "Starting"
            self._start()

    @property
    def enabled(self):
        return self.status != "Off"

    def close(self):
        self._closed = True
        self.status = "Off"
        if self._retry:
            self._retry.cancel()
        for server in self._servers.values():
            server.shutdown()
            server.server_close()
        self._servers = {}
        self.addresses = []
        if self._udp:
            self._udp.close()
            self._udp = None

    def pairing_code(self):
        """Everything the phone needs, as one string to copy."""
        return format_pairing_code(self.config, self.addresses, self._fingerprint)

    def _hello(self):
        return {"app": "longarm", "name": socket.gethostname(), "port": int(self.config.get("port", 47126)),
                "fingerprint": self._fingerprint}

    def _answer_discovery(self):
        """Replies to local-network broadcasts from private addresses with this machine's public details."""
        udp = self._udp
        while udp is self._udp:
            try:
                data, sender = udp.recvfrom(64)
            except OSError:
                return
            if data == DISCOVERY_REQUEST and ipaddress.ip_address(sender[0]).is_private:
                try:
                    udp.sendto(json.dumps(self._hello()).encode(), sender)
                except OSError:
                    pass

    def _start_discovery(self):
        if self._udp or self._closed:
            return
        try:
            self._udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._udp.bind(("", DISCOVERY_PORT))
        except OSError as e:
            log.warning("Phone API can't answer discovery: %s", e)
            self._udp = None
            return
        threading.Thread(target=self._answer_discovery, daemon=True, name="phone-discovery").start()

    def peers(self):
        """Longarm machines among the online peers on this Tailscale network."""
        port = int(self.config.get("port", 47126))
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            found = pool.map(lambda peer: _probe(peer[0], peer[1], port), desktop.tailscale_peers())
            return [device for device in found if device]

    def _request_pairing(self, body):
        nonce, phone = body.get("nonce"), body.get("phone")
        if not isinstance(nonce, str) or not re.fullmatch(r"[0-9a-f]{32}", nonce):
            return 400, {"error": "expected nonce"}
        phone = re.sub(r"[^\w .'-]", "", phone if isinstance(phone, str) else "")[:40] or "A phone"
        with self._pair_lock:
            now = time.monotonic()
            if self._pair and self._pair["expires"] > now and self._pair["status"] == "pending":
                return 429, {"error": "another request is waiting"}
            if now - self._last_pair_at < PAIR_COOLDOWN_S:
                return 429, {"error": "try again shortly"}
            self._last_pair_at = now
            code = pair_code(self._fingerprint, nonce)
            self._pair = {"id": secrets.token_urlsafe(18), "code": code, "phone": phone,
                          "expires": now + PAIR_LIFETIME_S, "status": "pending"}
            request_id = self._pair["id"]
        if self.on_pair_request:
            self.on_pair_request(phone, code)
        return 202, {"id": request_id}

    def _poll_pairing(self, body):
        with self._pair_lock:
            pair = self._pair
            if not pair or not hmac.compare_digest(str(body.get("id", "")), pair["id"]):
                return 404, {"error": "no such request"}
            if pair["expires"] <= time.monotonic():
                self._pair = None
                return 410, {"status": "expired"}
            if pair["status"] != "approved":
                if pair["status"] == "denied":
                    self._pair = None
                return 200, {"status": pair["status"]}
            self._pair = None
        port = int(self.config.get("port", 47126))
        return 200, {"status": "approved", "name": socket.gethostname(), "token": self.config["token"],
                     "fingerprint": self._fingerprint, "addresses": [f"{a}:{port}" for a in self.addresses]}

    def answer_pairing(self, code, allow):
        """Approves or refuses the waiting request with this code. False if none matches."""
        with self._pair_lock:
            pair = self._pair
            if not pair or pair["status"] != "pending" or pair["expires"] <= time.monotonic() or pair["code"] != code:
                return False
            pair["status"] = "approved" if allow else "denied"
            return True

    def _start(self):
        if self._closed:
            return
        try:
            if not self._context:
                cert, key = ensure_certificate(self.cert_dir)
                self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                self._context.load_cert_chain(cert, key)
                self._fingerprint = certificate_fingerprint(cert)
        except (OSError, ssl.SSLError, subprocess.SubprocessError) as e:
            self.status = f"Certificate problem: {e}"
            log.error("Phone API has no certificate: %s", e)
            return
        problem = ""
        wanted = wanted_addresses(self.config)
        for address in wanted:
            if address in self._servers:
                continue
            try:
                server = self._make_server(address)
            except OSError as e:
                problem = str(e)
                log.warning("Phone API could not listen on %s: %s", address, e)
                continue
            self._servers[address] = server
            threading.Thread(target=server.serve_forever, daemon=True, name=f"phone-api-{address}").start()
        self.addresses = list(self._servers)
        self.status = "Listening" if self._servers else f"Waiting: {problem or 'no network'}"
        if self._servers:
            self._start_discovery()
        automatic = not self.config.get("listen")
        if len(self._servers) < len(wanted) or not self._servers or (automatic and len(self._servers) < 2):
            self._retry = threading.Timer(RETRY_S, self._start)
            self._retry.daemon = True
            self._retry.start()

    def _make_server(self, address):
        context = self._context

        class Server(ThreadingHTTPServer):
            daemon_threads = True

            def finish_request(self, request, client_address):
                """The TLS handshake runs on the connection's own thread, so a slow client can't hold up others."""
                request.settimeout(CONNECTION_TIMEOUT_S)
                try:
                    secure = context.wrap_socket(request, server_side=True)
                except (OSError, ssl.SSLError):
                    return
                try:
                    self.RequestHandlerClass(secure, client_address, self)
                finally:
                    secure.close()

            def handle_error(self, request, client_address):
                log.debug("Phone API connection failed", exc_info=True)

        return Server((address, int(self.config.get("port", 47126))), self._handler())

    def _full_state(self):
        state = self.get_state()
        extra = self.extra_state() if self.extra_state else {}
        return state and {**state, **extra, "screens": desktop.screens(), "volume": desktop.volume()}

    def _route(self, path, body):
        """(HTTP status, reply) for a POST."""
        if path in self.extra_routes:
            return self.extra_routes[path](body)
        if path in ("/keyboard", "/power") and self.on_command:
            allowed = KEYBOARD_KEYS if path == "/keyboard" else POWER_KEYS
            command = {k: v for k, v in body.items() if k in allowed}
            if not command:
                return 400, {"error": f"expected one of: {', '.join(allowed)}"}
            self.on_command(command)
            return 202, {"queued": True}
        if path == "/brightness":
            percent = body.get("percent")
            if not isinstance(body.get("screen"), str) or not _is_percent(percent):
                return 400, {"error": "expected screen and percent"}
            done = desktop.set_brightness(body["screen"], percent)
            return (200, {"ok": True}) if done else (502, {"error": "not set"})
        if path == "/volume":
            percent, muted = body.get("percent"), body.get("muted")
            if (percent is None and muted is None) or (percent is not None and not _is_percent(percent)) \
                    or (muted is not None and not isinstance(muted, bool)):
                return 400, {"error": "expected percent and/or muted"}
            return (200, {"ok": True}) if desktop.set_volume(percent, muted) else (502, {"error": "not set"})
        return 404, {"error": "not found"}

    def _handler(self):
        api = self

        class Handler(BaseHTTPRequestHandler):
            def _reply(self, code, body):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _authorized(self):
                sent = self.headers.get("Authorization", "").encode()
                if hmac.compare_digest(sent, f"Bearer {api.config['token']}".encode()):
                    return True
                self._reply(401, {"error": "unauthorized"})
                return False

            def do_GET(self):
                if self.path == "/hello":
                    return self._reply(200, api._hello())
                if not self._authorized():
                    return
                if self.path == "/peers":
                    return self._reply(200, {"peers": api.peers()})
                if self.path == "/specs":
                    api._specs = api._specs or desktop.specs()
                    return self._reply(200, {"specs": api._specs})
                if self.path != "/state":
                    return self._reply(404, {"error": "not found"})
                state = api._full_state()
                self._reply(200, state) if state else self._reply(503, {"error": "starting"})

            def do_POST(self):
                pairing = {"/pair/request": api._request_pairing, "/pair/poll": api._poll_pairing}.get(self.path)
                if not pairing and not self._authorized():
                    return
                try:
                    length = min(int(self.headers.get("Content-Length", 0)), MAX_BODY_BYTES)
                    body = json.loads(self.rfile.read(length) or b"{}")
                    if not isinstance(body, dict):
                        raise ValueError("expected a JSON object")
                except ValueError as e:
                    return self._reply(400, {"error": str(e)})
                self._reply(*(pairing(body) if pairing else api._route(self.path, body)))

            def log_message(self, *args):
                pass

        return Handler


def _is_percent(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value <= 100
