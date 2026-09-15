"""Vision-domain rules shared by corpus batch jobs and online AI serving."""

from .catalog import DAMAGES, DEFAULT_WORK_BY_DAMAGE, ESTIMATE_WORK_ALIASES, ESTIMATE_WORKS, PARTS, WORKS
from .damage_part_pairing import damage_part_roi_rows
from .dinov2 import DinoV2Embedder, EmbeddingRunError, EmbeddingSpec
from .normalizer import NormalizationError, normalize_detection, normalize_estimate_work, normalize_inference, normalize_repair_label
from .roi import (
    INPUT_SIZE, LETTERBOX_FILL, MASK_RULE_VERSION, MAX_ASPECT, MIN_SIDE, PAD_RATIO, PAD_RATIO_RANGE,
    PART_CLIP_RULE_VERSION, PART_CLIP_TOLERANCE_PX, PART_LINK_MIN_OVERLAP, PART_LINK_RULE_VERSION,
    PREPROCESSING_VERSION, QUALITY_GOOD, QUALITY_INVALID, QUALITY_LOW_CONFIDENCE, QUALITY_PARTIAL_PART,
    Roi, RoiError, feature_quality, is_closeup, letterbox, link_parts, make_roi, make_roi_for_group,
    mask_min_rect, overlap, part_clipped, preprocessing_version, primary_part, roi_box, roi_quality, source_bbox,
)
from .search_metadata import build_search_metadata

__all__ = [
    "DAMAGES", "DEFAULT_WORK_BY_DAMAGE", "ESTIMATE_WORKS", "ESTIMATE_WORK_ALIASES", "PARTS", "WORKS",
    "NormalizationError", "normalize_detection", "normalize_estimate_work", "normalize_inference", "normalize_repair_label",
    "damage_part_roi_rows", "build_search_metadata", "DinoV2Embedder", "EmbeddingRunError", "EmbeddingSpec",
    "INPUT_SIZE", "LETTERBOX_FILL", "MASK_RULE_VERSION", "MAX_ASPECT", "MIN_SIDE", "PAD_RATIO", "PAD_RATIO_RANGE",
    "PART_CLIP_RULE_VERSION", "PART_CLIP_TOLERANCE_PX", "PART_LINK_MIN_OVERLAP", "PART_LINK_RULE_VERSION",
    "PREPROCESSING_VERSION", "QUALITY_GOOD", "QUALITY_INVALID", "QUALITY_LOW_CONFIDENCE", "QUALITY_PARTIAL_PART",
    "Roi", "RoiError", "feature_quality", "is_closeup", "letterbox", "link_parts", "make_roi",
    "make_roi_for_group", "mask_min_rect", "overlap", "part_clipped", "preprocessing_version", "primary_part",
    "roi_box", "roi_quality", "source_bbox",
]
