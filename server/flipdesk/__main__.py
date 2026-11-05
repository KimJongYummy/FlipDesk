"""Entry point: `python -m flipdesk` (tray app) or `python -m flipdesk --no-tray` (console)."""
from __future__ import annotations

import argparse
import asyncio
import atexit
import logging
import os
import sys
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path

from .backend import create_backend
from .config import Config
from .server import FlipDeskServer
from .tlsutil import short_fingerprint


def _setup_logging(data_dir: Path, verbose: bool) -> None:
    handlers: list[logging.Handler] = [
        RotatingFileHandler(data_dir / "flipdesk.log", maxBytes=1_000_000, backupCount=2,
                            encoding="utf-8")]
    if sys.stderr is not None:  # pythonw / windowed exe has no console
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO, handlers=handlers,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    # pythonw has no console, so anything unhandled would vanish: put it in the log instead.
    log = logging.getLogger("flipdesk")
    sys.excepthook = lambda t, e, tb: log.critical("uncaught exception", exc_info=(t, e, tb))
    threading.excepthook = lambda a: log.critical(
        "uncaught exception in thread %s", a.thread.name if a.thread else "?",
        exc_info=(a.exc_type, a.exc_value, a.exc_traceback))
    atexit.register(lambda: log.info("FlipDesk stopped"))


async def _console(server: FlipDeskServer, pair: bool) -> None:
    await server.start()
    if pair and sys.stdout is not None:  # windowed exe / pythonw have no console to print to
        import qrcode
        s = server.pairing.open()
        qr = qrcode.QRCode(border=1)
        qr.add_data(server.pairing.qr_payload(s))
        try:
            qr.print_ascii(invert=True)
        except UnicodeEncodeError:  # console/pipe can't show block characters: show a PNG instead
            png = server.config.dir / "pairing-qr.png"
            qr.make_image().save(png)
            print(f"Pairing QR saved to {png}")
            if sys.platform == "win32":
                os.startfile(png)
        print(f"\nPIN: {s.pin}   security code: {short_fingerprint(server.fingerprint)}"
              f"   (valid 5 minutes)\n", flush=True)
    try:
        await asyncio.Future()
    finally:
        await server.stop()


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="flipdesk", description="FlipDesk desktop server")
    ap.add_argument("--no-tray", action="store_true", help="run in the console without a tray icon")
    ap.add_argument("--pair", action="store_true", help="open pairing immediately")
    ap.add_argument("--dry-run", action="store_true", help="log input instead of injecting it")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int)
    ap.add_argument("--data-dir", type=Path)
    ap.add_argument("--no-discovery", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args(argv)

    config = Config(a.data_dir)
    _setup_logging(config.dir, a.verbose)
    logging.getLogger("flipdesk").info("FlipDesk starting (%s)", "tray" if not a.no_tray else "console")
    backend = create_backend(a.dry_run)
    server = FlipDeskServer(config, backend, host=a.host, port=a.port, discovery=not a.no_discovery)
    first_run = not config.devices()

    if a.no_tray:
        try:
            asyncio.run(_console(server, a.pair or first_run))
        except KeyboardInterrupt:
            pass
        return
    from .tray import TrayApp
    TrayApp(server).run(open_pairing=a.pair or first_run)


if __name__ == "__main__":
    main()
