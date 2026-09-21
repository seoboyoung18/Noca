import unittest
from types import SimpleNamespace

from pipeline.jobs.evaluation.render_guide_yolo_boost_strategy_ab import effective_boost


class WeightedBoostTest(unittest.TestCase):
    def hit(self, code="FRONT_BUMPER", confidence=1.0, overlap=1.0, matched=True):
        return SimpleNamespace(corpus_part_code=code, corpus_part_confidence=confidence,
                               corpus_part_overlap=overlap, corpus_part_matched=matched)

    def test_full_confidence_and_overlap(self):
        self.assertAlmostEqual(effective_boost(self.hit(), 0.05), 0.05)

    def test_low_confidence(self):
        self.assertAlmostEqual(effective_boost(self.hit(confidence=0.4), 0.05), 0.02)

    def test_partial_overlap(self):
        self.assertAlmostEqual(effective_boost(self.hit(confidence=0.6, overlap=0.5), 0.05), 0.015)

    def test_unmatched_or_null_is_zero(self):
        self.assertEqual(effective_boost(self.hit(matched=False), 0.05), 0.0)
        self.assertEqual(effective_boost(self.hit(confidence=None), 0.05), 0.0)


if __name__ == "__main__":
    unittest.main()
