"""Windowless launcher (used by start-with-Windows and double-click)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from flipdesk.__main__ import main  # noqa: E402

main()
