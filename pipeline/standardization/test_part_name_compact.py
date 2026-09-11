"""compact() 비교 키의 계약을 fixture 로 고정한다 (Python 쪽).

같은 fixture 파일을 Java 쪽
backend/src/test/java/com/ssafy/a307/estimatevalidation/service/PartNameCompactKeyFixtureTest.java
가 다시 읽는다. 한쪽 구현만 손대면 반대쪽 테스트가 깨지므로 두 구현이 조용히 갈라지지 않는다.

compact() 는 jobs/map_estimate_labels.py 안에 있는데 그 스크립트는 import 시점에
argparse 로 --audit-json / --output 을 강제한다. 그래서 import 대신 소스에서 함수 정의만
떼어내 실행한다. 사본을 두지 않으므로 원본을 고치면 이 테스트가 바로 반응한다.
"""

import ast
import re
import unicodedata
import unittest
from pathlib import Path

PIPELINE_ROOT = Path(__file__).resolve().parents[1]
SOURCE = PIPELINE_ROOT / "jobs" / "map_estimate_labels.py"
FIXTURE = Path(__file__).parent / "fixtures" / "part_name_compact_fixture.tsv"
EMPTY_MARKER = "<EMPTY>"
REMOVED_CHARACTERS = [" ", "_", "-", "\uff0d", "\u00b7", ".", ",", "/"]


def load_compact():
    """map_estimate_labels.py 의 compact() 정의만 떼어내 실제 함수 객체로 만든다."""
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"), filename=str(SOURCE))
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "compact":
            namespace = {"re": re, "unicodedata": unicodedata}
            exec(compile(ast.Module([node], []), str(SOURCE), "exec"), namespace)
            return namespace["compact"]
    raise AssertionError(f"{SOURCE} 에 compact() 정의가 없다")


def load_fixture():
    rows = []
    for line in FIXTURE.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        source, expected = line.split("\t", 1)
        rows.append((source, "" if expected == EMPTY_MARKER else expected))
    return rows


class PartNameCompactFixtureTest(unittest.TestCase):
    def setUp(self):
        self.compact = load_compact()
        self.rows = load_fixture()

    def test_fixture_is_loaded(self):
        """경로가 어긋나면 0건이 되어 아무것도 검증하지 않은 채 통과한다."""
        self.assertGreater(len(self.rows), 20)

    def test_fixture_matches_compact(self):
        for source, expected in self.rows:
            with self.subTest(source=source):
                self.assertEqual(self.compact(source), expected)

    def test_fixture_covers_every_removed_character(self):
        """제거 대상 문자가 fixture 에서 빠지면 Java 쪽 회귀를 잡지 못한다."""
        sources = "".join(source for source, _ in self.rows)
        for character in REMOVED_CHARACTERS:
            with self.subTest(character=character):
                self.assertIn(character, sources)

    def test_compact_is_idempotent(self):
        """한 번 접은 키를 다시 접어도 같아야 관리자 검사와 seed 판정이 어긋나지 않는다."""
        for _, expected in self.rows:
            with self.subTest(expected=expected):
                self.assertEqual(self.compact(expected), expected)

    def test_none_of_the_fixture_targets_the_runtime_normalizer(self):
        """compact() 는 괄호·작업 접미사·좌우 표기를 건드리지 않는다.

        런타임 조회용 PartNameNormalizer 는 셋 다 건드린다. 두 키를 혼동해
        fixture 에 런타임 기대값을 적어 넣는 실수를 막는다.
        """
        self.assertEqual(self.compact("앞범퍼(좌)"), "앞범퍼(좌)")
        self.assertEqual(self.compact("앞범퍼 교환"), "앞범퍼교환")
        self.assertEqual(self.compact("왼쪽 도어"), "왼쪽도어")


if __name__ == "__main__":
    unittest.main()
