"""Shared standard codes and normalization for the barun-quote data pipeline."""

from .catalog import (
    DAMAGES,
    DEFAULT_WORK_BY_DAMAGE,
    ESTIMATE_WORK_ALIASES,
    ESTIMATE_WORKS,
    PARTS,
    WORKS,
)
from .normalizer import (
    NormalizationError,
    normalize_detection,
    normalize_estimate_work,
    normalize_inference,
    normalize_repair_label,
)

__all__ = [
    "DAMAGES",
    "DEFAULT_WORK_BY_DAMAGE",
    "ESTIMATE_WORKS",
    "ESTIMATE_WORK_ALIASES",
    "PARTS",
    "WORKS",
    "NormalizationError",
    "normalize_detection",
    "normalize_estimate_work",
    "normalize_inference",
    "normalize_repair_label",
]
