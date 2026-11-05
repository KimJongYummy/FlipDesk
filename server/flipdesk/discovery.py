"""LAN auto-discovery: answer UDP broadcast probes so the phone can find us after an IP change."""
from __future__ import annotations

import asyncio
import json
import logging
import socket

from .protocol import DISCOVERY_PROBE, DISCOVERY_REPLY_TYPE, PROTOCOL_VERSION

log = logging.getLogger(__name__)


class DiscoveryResponder(asyncio.DatagramProtocol):
    def __init__(self, server_id: str, name: str, port: int, fingerprint: str):
        self.reply = json.dumps({
            "t": DISCOVERY_REPLY_TYPE, "v": PROTOCOL_VERSION, "id": server_id,
            "name": name, "port": port, "fp": fingerprint,
        }).encode()
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport):  # type: ignore[override]
        self.transport = transport

    def datagram_received(self, data: bytes, addr):  # type: ignore[override]
        if data.startswith(DISCOVERY_PROBE) and self.transport:
            # The reply only reveals what the QR code already shows; auth still needs a token.
            self.transport.sendto(self.reply, addr)


async def start_discovery(host: str, port: int, server_id: str, name: str, tcp_port: int,
                          fingerprint: str) -> asyncio.DatagramTransport | None:
    loop = asyncio.get_running_loop()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind((host, port))
    except OSError as e:
        log.warning("discovery disabled: cannot bind UDP %s:%s (%s)", host, port, e)
        sock.close()
        return None
    transport, _ = await loop.create_datagram_endpoint(
        lambda: DiscoveryResponder(server_id, name, tcp_port, fingerprint), sock=sock)
    return transport
