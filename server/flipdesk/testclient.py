"""A fake phone: speaks the FlipDesk protocol exactly like the Android app does.

Used by the test suite, and handy by hand:
    python -m flipdesk.testclient --qr "flipdesk://pair?..."      # pair, then run a short demo
    python -m flipdesk.testclient --discover                       # list servers on the LAN
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import socket
import ssl
import uuid
from typing import Any
from urllib.parse import parse_qs, urlparse

from websockets.asyncio.client import ClientConnection, connect

from . import protocol as P


class PinMismatch(Exception):
    pass


def parse_qr(uri: str) -> dict[str, Any]:
    u = urlparse(uri)
    if u.scheme != "flipdesk" or u.netloc != "pair":
        raise ValueError("not a FlipDesk pairing code")
    q = {k: v[0] for k, v in parse_qs(u.query).items()}
    return {"hosts": q["h"].split(","), "port": int(q["p"]), "fp": q["fp"].lower(),
            "secret": q["s"], "server_id": q.get("id"), "name": q.get("n")}


def discover(timeout: float = 1.5, port: int = P.DISCOVERY_PORT,
             target: str = "255.255.255.255") -> list[dict[str, Any]]:
    found = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(timeout)
        s.sendto(P.DISCOVERY_PROBE + b'{"v":1}', (target, port))
        try:
            while True:
                data, addr = s.recvfrom(4096)
                info = json.loads(data)
                if info.get("t") == P.DISCOVERY_REPLY_TYPE:
                    info["host"] = addr[0]
                    found.append(info)
        except (socket.timeout, TimeoutError):
            pass
    return found


class FakePhone:
    def __init__(self, device_id: str | None = None, device_name: str = "Fake Flip8"):
        self.device_id = device_id or uuid.uuid4().hex
        self.device_name = device_name
        self.ws: ClientConnection | None = None
        self.token: str | None = None
        self.welcome: dict[str, Any] | None = None
        self._inbox: asyncio.Queue = asyncio.Queue()
        self._reader: asyncio.Task | None = None

    async def connect(self, host: str, port: int, fingerprint: str) -> None:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # we pin the exact certificate instead of trusting CAs
        self.ws = await connect(f"wss://{host}:{port}/", ssl=ctx, max_size=8 * 1024 * 1024,
                                compression=None, open_timeout=5)
        der = self.ws.transport.get_extra_info("ssl_object").getpeercert(binary_form=True)
        if hashlib.sha256(der).hexdigest() != fingerprint.lower():
            await self.ws.close()
            raise PinMismatch("server certificate does not match the pinned fingerprint")
        self._reader = asyncio.create_task(self._read())

    async def _read(self) -> None:
        try:
            async for raw in self.ws:
                await self._inbox.put(raw if isinstance(raw, bytes) else json.loads(raw))
        except Exception:
            pass
        await self._inbox.put(None)  # connection closed marker

    async def send(self, t: str, **fields: Any) -> None:
        await self.ws.send(P.encode(t, **fields))

    async def send_bytes(self, data: bytes) -> None:
        await self.ws.send(data)

    async def recv(self, t: str | None = None, timeout: float = 5.0) -> Any:
        """Next JSON message (optionally of type t); binary frames are skipped unless t == 'binary'."""
        while True:
            item = await asyncio.wait_for(self._inbox.get(), timeout)
            if item is None:
                raise ConnectionError("connection closed")
            if t == "binary":
                if isinstance(item, bytes):
                    return item
                continue
            if isinstance(item, bytes):
                continue
            if t is None or item.get("t") == t:
                return item

    async def pair(self, secret: str | None = None, pin: str | None = None) -> dict[str, Any]:
        await self.send("pair", v=P.PROTOCOL_VERSION, device_id=self.device_id,
                        device_name=self.device_name, **({"secret": secret} if secret else {"pin": pin}))
        first = await self.recv()
        if first["t"] != "paired":
            return first
        self.token = first["token"]
        self.welcome = await self.recv("auth_ok")
        return first

    async def hello(self, token: str | None = None) -> dict[str, Any]:
        await self.send("hello", v=P.PROTOCOL_VERSION, device_id=self.device_id,
                        token=token or self.token)
        msg = await self.recv()
        if msg["t"] == "auth_ok":
            self.welcome = msg
        return msg

    async def close(self) -> None:
        if self.ws:
            await self.ws.close()
        if self._reader:
            await asyncio.gather(self._reader, return_exceptions=True)


async def _demo(args) -> None:
    if args.discover:
        for s in discover():
            print(f"{s['name']} at {s['host']}:{s['port']} fp={s['fp'][:16]}…")
        return
    info = parse_qr(args.qr)
    phone = FakePhone()
    await phone.connect(info["hosts"][0], info["port"], info["fp"])
    res = await phone.pair(secret=info["secret"])
    print("pair:", res["t"], "| monitors:", len((phone.welcome or {}).get("monitors", [])))
    await phone.send("mouse_move", dx=20, dy=0)
    await phone.send("mouse_move", dx=-20, dy=0)
    await phone.send("ping", ts=1)
    print("pong:", await phone.recv("pong"))
    await phone.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--qr")
    ap.add_argument("--discover", action="store_true")
    asyncio.run(_demo(ap.parse_args()))
