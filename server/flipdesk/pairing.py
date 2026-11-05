"""Pairing (QR one-time secret or 6-digit PIN) and device-token authentication."""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import secrets
import socket
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlencode

from .config import Config

PAIRING_WINDOW_S = 5 * 60
MAX_PIN_FAILURES = 5


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def lan_addresses() -> list[str]:
    """Private IPv4 addresses of this machine, the default-route one first."""
    ips: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # TEST-NET; no packet is sent for UDP connect
            ips.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    good = [ip for ip in ips if ipaddress.ip_address(ip).is_private
            and not ipaddress.ip_address(ip).is_loopback
            and not ip.startswith("169.254.")]
    return good or ["127.0.0.1"]


@dataclass
class PairingSession:
    secret: str
    pin: str
    expires: float
    pin_failures: int = 0


class PairingManager:
    def __init__(self, config: Config, fingerprint: str, port: int,
                 on_change: Callable[[], None] | None = None):
        self.config = config
        self.fingerprint = fingerprint
        self.port = port
        self.on_change = on_change
        self._lock = threading.Lock()
        self._session: PairingSession | None = None

    # --- pairing window ---------------------------------------------------------
    def open(self, duration: float = PAIRING_WINDOW_S) -> PairingSession:
        with self._lock:
            self._session = PairingSession(
                secret=secrets.token_urlsafe(18),
                pin=f"{secrets.randbelow(1_000_000):06d}",
                expires=time.monotonic() + duration,
            )
            s = self._session
        self._changed()
        return s

    def close(self) -> None:
        with self._lock:
            self._session = None
        self._changed()

    def active(self) -> PairingSession | None:
        with self._lock:
            if self._session and time.monotonic() > self._session.expires:
                self._session = None
            return self._session

    def qr_payload(self, s: PairingSession) -> str:
        q = urlencode({
            "v": 1, "h": ",".join(lan_addresses()), "p": self.port, "fp": self.fingerprint,
            "s": s.secret, "id": self.config.server_id, "n": self.config.name,
        })
        return f"flipdesk://pair?{q}"

    # --- pairing / auth ---------------------------------------------------------
    def try_pair(self, msg: dict[str, Any]) -> tuple[str | None, str]:
        """Returns (token, "") on success or (None, reason)."""
        device_id = msg.get("device_id")
        if not isinstance(device_id, str) or not 8 <= len(device_id) <= 128:
            return None, "bad device_id"
        name = str(msg.get("device_name") or "Phone")[:64]
        with self._lock:
            s = self._session
            if s is None or time.monotonic() > s.expires:
                self._session = None
                return None, "pairing is not open on the computer"
            secret, pin = msg.get("secret"), msg.get("pin")
            if isinstance(secret, str) and secret:
                ok = hmac.compare_digest(secret, s.secret)
            elif isinstance(pin, str) and pin:
                ok = hmac.compare_digest(pin, s.pin)
                if not ok:
                    s.pin_failures += 1
                    if s.pin_failures >= MAX_PIN_FAILURES:
                        self._session = None  # force a new code after too many guesses
            else:
                ok = False
            if not ok:
                return None, "wrong pairing code"
            self._session = None  # one-time: secret/PIN are consumed by a successful pair
        token = secrets.token_urlsafe(32)
        self.config.save_device(device_id, {
            "name": name, "token_sha256": _hash_token(token),
            "paired_at": int(time.time()), "last_seen": int(time.time()),
        })
        self._changed()
        return token, ""

    def authenticate(self, msg: dict[str, Any]) -> dict[str, Any] | None:
        device_id, token = msg.get("device_id"), msg.get("token")
        if not isinstance(device_id, str) or not isinstance(token, str):
            return None
        rec = self.config.devices().get(device_id)
        if not rec or not hmac.compare_digest(rec.get("token_sha256", ""), _hash_token(token)):
            return None
        return rec

    def touch(self, device_id: str) -> None:
        rec = self.config.devices().get(device_id)
        if rec:
            rec["last_seen"] = int(time.time())
            self.config.save_device(device_id, rec)

    def _changed(self) -> None:
        if self.on_change:
            try:
                self.on_change()
            except Exception:
                pass
