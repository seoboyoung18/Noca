"""Stable storage keys shared by search-data loaders."""

from __future__ import annotations

import os
import re
from pathlib import Path


_SOURCE_IMAGE_RE = re.compile(
    r"(?P<image_id>\d+)_(?:as|sc)-\d+\.(?P<extension>[A-Za-z0-9]+)$",
    re.IGNORECASE,
)
_VARIANTS = {"original", "resized", "thumbnail", "blurred"}


def _validate_segment(name: str, value: str) -> None:
    if value is None or not str(value).strip():
        raise ValueError(f"{name} is required")
    if "/" in value or "\\" in value or ".." in value:
        raise ValueError(f"{name} must be a safe path segment")


def source_image_id_from_ref(source_image_ref: str) -> str:
    """Extract the zero-preserving numeric prefix from an AI-Hub image ref."""
    name = Path(source_image_ref.replace("/", os.sep)).name
    match = _SOURCE_IMAGE_RE.fullmatch(name)
    if not match:
        raise ValueError(
            "source_image_ref must end with <image_id>_<external_ref>.<ext>: "
            f"{source_image_ref}"
        )
    return match.group("image_id")


def repair_case_image_key(
    source: str,
    external_ref: str,
    source_image_id: str,
    variant: str = "original",
    extension: str = "jpg",
) -> str:
    """Build ``repair-cases/{source}/{external_ref}/{source_image_id}/...``."""
    _validate_segment("source", source)
    _validate_segment("external_ref", external_ref)
    _validate_segment("source_image_id", source_image_id)
    if variant not in _VARIANTS:
        raise ValueError(f"unsupported image variant: {variant}")
    if extension is None or not extension.strip():
        raise ValueError("extension is required")
    extension = extension.strip().lower().lstrip(".")
    if not extension or "/" in extension or "\\" in extension or "." in extension:
        raise ValueError("extension must not contain path separators or dots")
    key = f"repair-cases/{source}/{external_ref}/{source_image_id}/{variant}.{extension}"
    if len(key) > 500:
        raise ValueError("s3 key must not exceed 500 characters")
    return key


def repair_case_image_key_from_ref(
    source: str,
    external_ref: str,
    source_image_ref: str,
    variant: str = "original",
    extension: str = "jpg",
) -> str:
    return repair_case_image_key(
        source, external_ref, source_image_id_from_ref(source_image_ref), variant, extension
    )
