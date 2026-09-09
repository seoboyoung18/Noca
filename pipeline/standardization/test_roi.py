"""roi.py 회귀 테스트 — 설계 4·5절 조항별 대조를 포함한다."""
import unittest
from PIL import Image
from standardization import (
    PAD_RATIO, PAD_RATIO_RANGE, INPUT_SIZE, MIN_SIDE, MAX_ASPECT,
    QUALITY_GOOD, QUALITY_LOW_CONFIDENCE, QUALITY_INVALID, QUALITY_PARTIAL_PART,
    RoiError, Roi, feature_quality, is_closeup, letterbox, link_parts, make_roi,
    make_roi_for_group, mask_min_rect, overlap, part_clipped, preprocessing_version,
    primary_part, roi_box, roi_quality, source_bbox,
)

IMG = (800, 600)
SEG = [[[[303, 411], [306, 409], [420, 422], [542, 413], [543, 421],
         [483, 427], [358, 429], [303, 411]]]]   # 원천 as-0000002 id=2


class TestDesignDefaults(unittest.TestCase):
    """설계에 없는 규칙은 기본 비활성이어야 한다"""

    def test_experiment_knobs_off_by_default(self):
        self.assertEqual(MIN_SIDE, 0)
        self.assertEqual(MAX_ASPECT, 0.0)

    def test_default_padding_is_20pct(self):
        self.assertAlmostEqual(PAD_RATIO, 0.20)

    def test_default_padding_exact(self):
        """설계 4절: bbox 가로·세로의 20%. 다른 규칙이 끼어들지 않는다"""
        (x0, y0, x1, y1), eff, clipped, _ = roi_box((300, 200, 100, 50), IMG)
        self.assertEqual((x0, y0, x1, y1), (280, 190, 420, 260))
        self.assertAlmostEqual(eff[0], 0.20, places=6)
        self.assertAlmostEqual(eff[1], 0.20, places=6)
        self.assertFalse(clipped)

    def test_tiny_bbox_keeps_20pct(self):
        """v2 회귀: min_side가 20% 패딩을 덮어쓰면 안 된다"""
        _, eff, _, _ = roi_box((400, 300, 15, 28), IMG)
        self.assertAlmostEqual(eff[0], 0.20, places=6)
        self.assertAlmostEqual(eff[1], 0.20, places=6)

    def test_extreme_aspect_preserved(self):
        """종횡비 상한이 기본으로 개입하면 안 된다"""
        (x0, y0, x1, y1), eff, _, _ = roi_box((100, 300, 240, 20), IMG)
        self.assertAlmostEqual((x1 - x0) / (y1 - y0), 336 / 28, places=1)
        self.assertAlmostEqual(eff[0], 0.20, places=6)

    def test_pad_ratio_range_is_effective(self):
        """15~30% 실험이 실제로 결과를 바꿔야 한다"""
        widths = set()
        for p in (0.15, 0.20, 0.25, 0.30):
            (x0, _, x1, _), eff, _, _ = roi_box((400, 300, 15, 28), IMG, pad_ratio=p)
            widths.add(x1 - x0)
            self.assertAlmostEqual(eff[0], p, places=6)
        self.assertEqual(len(widths), 4)


class TestBoundary(unittest.TestCase):
    def test_clip_not_shift(self):
        """설계 4절: 경계는 '원본 크기로 제한'(clip). 밀어넣지 않는다"""
        (x0, y0, x1, y1), _, clipped, _ = roi_box((0, 0, 20, 20), IMG)
        self.assertEqual((x0, y0), (0, 0))
        self.assertTrue(clipped)
        self.assertLess(x1 - x0, 20 * 1.4 + 1)

    def test_clipped_flag_and_effective_padding(self):
        _, eff, clipped, _ = roi_box((0, 0, 100, 100), IMG)
        self.assertTrue(clipped)
        self.assertLess(eff[0], PAD_RATIO)   # 왼쪽이 잘려 유효 패딩이 준다

    def test_stays_in_bounds(self):
        for bbox in [(0, 0, 12, 12), (795, 595, 5, 5), (0, 300, 4, 200), (790, 0, 10, 10)]:
            (x0, y0, x1, y1), _, _, _ = roi_box(bbox, IMG)
            self.assertGreaterEqual(min(x0, y0), 0)
            self.assertLessEqual(x1, IMG[0])
            self.assertLessEqual(y1, IMG[1])

    def test_empty_after_clip_raises(self):
        with self.assertRaises(RoiError):
            roi_box((10, 10, 5, 5), (0, 0))


class TestMaskRule(unittest.TestCase):
    """설계 4절: segmentation이 있으면 mask 최소 bounding rect 사용"""

    def test_mask_min_rect(self):
        self.assertEqual(mask_min_rect(SEG), (303.0, 409.0, 240.0, 20.0))

    def test_mask_none_when_empty(self):
        self.assertIsNone(mask_min_rect([]))
        self.assertIsNone(mask_min_rect(None))

    def test_source_bbox_prefers_mask(self):
        ann = {"bbox": [303, 409, 240, 20], "segmentation": SEG}
        self.assertEqual(source_bbox(ann), (303.0, 409.0, 240.0, 20.0))

    def test_real_label_bbox_equals_mask_rect(self):
        """실측 99.45%: AI-Hub bbox는 폴리곤 최소 rect와 같다"""
        self.assertEqual(mask_min_rect(SEG), (303.0, 409.0, 240.0, 20.0))
        self.assertEqual(source_bbox({"bbox": [303, 409, 240, 20],
                                      "segmentation": SEG}),
                         (303.0, 409.0, 240.0, 20.0))

    def test_source_bbox_falls_back(self):
        self.assertEqual(source_bbox({"bbox": [1, 2, 3, 4], "segmentation": None}),
                         (1.0, 2.0, 3.0, 4.0))

    def test_source_bbox_raises_without_either(self):
        with self.assertRaises(RoiError):
            source_bbox({"id": 7})


class TestQuality(unittest.TestCase):
    """설계 6절 허용값: GOOD / LOW_CONFIDENCE / INVALID"""

    def test_vocabulary(self):
        self.assertEqual({QUALITY_GOOD, QUALITY_LOW_CONFIDENCE, QUALITY_INVALID},
                         {"GOOD", "LOW_CONFIDENCE", "INVALID"})

    def test_small_is_low_confidence(self):
        self.assertEqual(roi_quality((0, 0, 15, 28)), QUALITY_LOW_CONFIDENCE)

    def test_normal_is_good(self):
        self.assertEqual(roi_quality((0, 0, 52, 63)), QUALITY_GOOD)

    def test_degenerate_is_invalid(self):
        self.assertEqual(roi_quality((0, 0, 0, 10)), QUALITY_INVALID)

    def test_low_confidence_by_confidence(self):
        self.assertEqual(roi_quality((0, 0, 52, 63), confidence=0.1, min_confidence=0.4),
                         QUALITY_LOW_CONFIDENCE)

    def test_low_confidence_not_dropped(self):
        """설계는 표시만 요구한다. 색인 제외는 호출부 정책"""
        _, meta = make_roi(Image.new("RGB", IMG), {"bbox": [100, 100, 15, 28]})
        self.assertEqual(meta.quality_status, QUALITY_LOW_CONFIDENCE)
        self.assertFalse(hasattr(meta, "is_searchable"))


class TestLetterbox(unittest.TestCase):
    def test_upscales(self):
        out = letterbox(Image.new("RGB", (15, 28), (200, 10, 10)))
        self.assertEqual(out.size, (INPUT_SIZE, INPUT_SIZE))
        self.assertEqual(out.load()[INPUT_SIZE // 2, INPUT_SIZE // 2], (200, 10, 10))

    def test_downscales(self):
        self.assertEqual(letterbox(Image.new("RGB", (2000, 900))).size,
                         (INPUT_SIZE, INPUT_SIZE))

    def test_aspect_preserved(self):
        out = letterbox(Image.new("RGB", (400, 100), (0, 200, 0)))
        px = out.load()
        self.assertEqual(px[INPUT_SIZE // 2, INPUT_SIZE // 2], (0, 200, 0))
        self.assertEqual(px[INPUT_SIZE // 2, 4], (114, 114, 114))


class TestPartLink(unittest.TestCase):
    """설계 5절"""

    PARTS = [{"part": "Front bumper", "bbox": [42, 179, 569, 356]},
             {"part": "Front fender(L)", "bbox": [180, 330, 120, 120]}]

    def test_primary_is_max_overlap(self):
        """포개진 부품에서 동률이면 더 구체적인(작은) 부품을 고른다"""
        d = {"bbox": [204, 354, 52, 63]}
        self.assertEqual(primary_part(d, self.PARTS)["part"], "Front fender(L)")

    def test_nested_parts_tie_at_one(self):
        """설계 공식 intersection/area(damage)는 포개진 부품에서 둘 다 1.0"""
        d = {"bbox": [204, 354, 52, 63]}
        for part in self.PARTS:
            self.assertAlmostEqual(overlap(d["bbox"], part["bbox"]), 1.0)

    def test_multiple_parts_returned(self):
        """경계에 걸친 손상은 복수 부품과 연결될 수 있다"""
        linked = link_parts({"bbox": [204, 354, 52, 63]}, self.PARTS)
        self.assertEqual(len(linked), 2)
        self.assertGreaterEqual(linked[0][1], linked[1][1])

    def test_no_link_is_not_error(self):
        self.assertEqual(link_parts({"bbox": [700, 10, 20, 20]}, self.PARTS), [])
        self.assertIsNone(primary_part({"bbox": [700, 10, 20, 20]}, self.PARTS))

    def test_overlap_bounds(self):
        self.assertAlmostEqual(overlap((10, 10, 10, 10), (0, 0, 100, 100)), 1.0)
        self.assertAlmostEqual(overlap((200, 200, 10, 10), (0, 0, 100, 100)), 0.0)


class TestGroupHandling(unittest.TestCase):
    """설계 3절"""

    def test_closeup_detection(self):
        self.assertTrue(is_closeup((0, 0, 700, 500), IMG))
        self.assertFalse(is_closeup((0, 0, 50, 50), IMG))

    def test_damage_closeup_uses_whole_image(self):
        _, meta, view = make_roi_for_group(
            Image.new("RGB", IMG), {"bbox": [10, 10, 700, 500]}, "damage")
        self.assertEqual(view, "CLOSEUP")
        self.assertEqual(meta.roi_bbox, (0, 0, 800, 600))

    def test_damage_part_is_full_vehicle(self):
        _, _, view = make_roi_for_group(
            Image.new("RGB", IMG), {"bbox": [100, 100, 52, 63]}, "damage_part")
        self.assertEqual(view, "FULL_VEHICLE")

    def test_damage_small_is_part_view(self):
        _, _, view = make_roi_for_group(
            Image.new("RGB", IMG), {"bbox": [100, 100, 52, 63]}, "damage")
        self.assertEqual(view, "PART_VIEW")


class TestMeta(unittest.TestCase):
    def test_padding_ratio_reproducible(self):
        """저장한 값으로 ROI를 재현할 수 있어야 한다"""
        img, meta = make_roi(Image.new("RGB", IMG), {"bbox": [300, 200, 100, 50]})
        again, _, _, _ = roi_box((300, 200, 100, 50), IMG, pad_ratio=meta.padding_ratio)
        self.assertEqual(meta.box, again)

    def test_design_conformance_flag(self):
        _, meta = make_roi(Image.new("RGB", IMG), {"bbox": [300, 200, 100, 50]})
        self.assertTrue(meta.is_design_conformant)

    def test_clipping_is_not_a_violation(self):
        """경계 clip은 설계가 지시한 동작이다"""
        _, meta = make_roi(Image.new("RGB", IMG), {"bbox": [0, 0, 100, 100]})
        self.assertTrue(meta.clipped)
        self.assertTrue(meta.is_design_conformant)
        _, meta2 = make_roi(Image.new("RGB", IMG), {"bbox": [400, 300, 15, 28]},
                            min_side=64)
        self.assertFalse(meta2.is_design_conformant)

    def test_version_string_reflects_knobs(self):
        self.assertEqual(preprocessing_version(), "pad20-lb224gray")
        self.assertEqual(preprocessing_version(0.20, 64, 3.0), "pad20-min64-ar3-lb224gray")
        self.assertEqual(preprocessing_version(0.15), "pad15-lb224gray")

    def test_experiment_knobs_still_available(self):
        _, eff, _, _ = roi_box((400, 300, 15, 28), IMG, min_side=64)
        self.assertGreater(eff[0], 0.20)

    def test_pad_range_constant(self):
        self.assertEqual(PAD_RATIO_RANGE, (0.15, 0.30))


class TestPartClip(unittest.TestCase):
    """부품 잘림은 좌표 관측이다. 부품 전체 크기를 추정하지 않는다."""

    def test_touching_each_edge_is_clipped(self):
        for box in [(0, 40, 100, 80), (40, 0, 100, 80),
                    (700, 40, 100, 80), (40, 520, 100, 80)]:
            with self.subTest(box=box):
                self.assertTrue(part_clipped(box, (800, 600)))

    def test_inside_image_is_not_clipped(self):
        self.assertFalse(part_clipped((40, 40, 100, 80), (800, 600)))

    def test_degenerate_box_raises(self):
        with self.assertRaises(RoiError):
            part_clipped((10, 10, 0, 80), (800, 600))

    def test_feature_quality_layers_on_roi_status(self):
        self.assertEqual(feature_quality(QUALITY_GOOD, True), QUALITY_PARTIAL_PART)
        self.assertEqual(feature_quality(QUALITY_GOOD, False), QUALITY_GOOD)
        # 모르면 바꾸지 않는다
        self.assertEqual(feature_quality(QUALITY_GOOD, None), QUALITY_GOOD)
        # 손상을 못 믿으면 부품 잘림을 따지지 않는다
        self.assertEqual(
            feature_quality(QUALITY_LOW_CONFIDENCE, True), QUALITY_LOW_CONFIDENCE)
        self.assertEqual(feature_quality(QUALITY_INVALID, True), QUALITY_INVALID)


if __name__ == "__main__":
    unittest.main(verbosity=2)
