"""Pick the input/OS backend for this platform."""
from __future__ import annotations

import sys

from .base import Backend
from .dryrun import DryRunBackend


def create_backend(dry_run: bool = False) -> Backend:
    if dry_run:
        return DryRunBackend()
    if sys.platform == "win32":
        from .windows import WindowsBackend
        return WindowsBackend()
    raise SystemExit(f"FlipDesk server has no input backend for {sys.platform} yet; use --dry-run")


__all__ = ["Backend", "DryRunBackend", "create_backend"]
