"""Make the repository's shared pipeline package importable from AI/server."""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]


def ensure_repo_root() -> None:
    value = str(REPO_ROOT)
    if value not in sys.path:
        sys.path.insert(0, value)

