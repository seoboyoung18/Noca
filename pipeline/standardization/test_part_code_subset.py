"""파이프라인이 붙일 수 있는 부품 코드가 `part_code` seed 56종의 부분집합인지.

`map_estimate_labels.py` 는 자기 안에 견적 전용 확장 코드를 직접 적어 두고,
`Docs/Erd/A307_part_code_seed.sql` 은 DB 마스터를 따로 적어 둔다. 둘이 어긋나면
파이프라인이 만든 매핑 행이 FK 위반으로 적재에 실패하거나(운이 좋은 경우),
관리자 화면에서 존재하지 않는 코드를 가리키게 된다.

이 테스트는 그 어긋남을 자동으로 잡는다. 판정 패턴을 `catalog.py` 로 옮기는
리팩터링은 범위가 커서 하지 않았고, 대신 불일치를 여기서 잠근다.
"""
from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MAPPER = REPO_ROOT / "pipeline" / "jobs" / "map_estimate_labels.py"
PART_CODE_SEED = REPO_ROOT / "Docs" / "Erd" / "A307_part_code_seed.sql"

#: 소스가 `standard_code` 에 넣는 표현식 전체. 새 코드가 추가되면 이 집합이 달라지고
#: `test_no_unknown_standard_code_producer` 가 먼저 깨진다 — 아래 유도 규칙을 갱신하라는 신호다.
KNOWN_PRODUCERS = {
    '""',
    'f"{family}_L,{family}_R"',      # MAPPED_SPLIT — DB 제외
    'f"{family}_UNKNOWN"',           # MAPPED_SIDE_UNKNOWN — DB 제외
    'f"{family}_{side}"',            # 좌/우 확정
    'f"PARKING_SENSOR_{sensor_dir}"',
    'family',
    # 견적서 '작업' 열의 작업 코드다. 부품 코드가 아니라 ESTIMATE_WORKS 어휘이므로
    # part_code seed 와 대조하지 않는다 — 두 어휘를 억지로 합치지 않는다는 결정.
    'work',
    '"REAR_BUMPER_UNDERCOVER"',
    '"RADIATOR_GRILLE"',
    '"BACK_PANEL"',
    '"COWL_GRILLE"',
    '"WASHER_TANK"',
    '"EMBLEM"',
    '"FRONT_PANEL"',
    '"REAR_CAMERA"',
}

#: f-string 으로 만들어지는 방향 접미사. 소스의 분기에서 나오는 값 그대로다.
DIRECTIONS = ("FRONT", "REAR")


def source() -> str:
    return MAPPER.read_text(encoding="utf-8")


def standard_code_producers() -> set[str]:
    """소스에서 `"standard_code": <표현식>` 의 우변을 원문 그대로 모은다."""
    return set(re.findall(r'"standard_code":\s*(f?"[^"]*"|\w+)', source()))


def families() -> list[tuple[str, bool]]:
    """FAMILIES 리터럴에서 (코드, 좌우구분여부) 를 꺼낸다."""
    tree = ast.parse(source(), filename=str(MAPPER))
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if not any(getattr(t, "id", None) == "FAMILIES" for t in node.targets):
            continue
        return [(entry.elts[0].value, entry.elts[2].value) for entry in node.value.elts]
    raise AssertionError("FAMILIES 리터럴을 찾지 못했다")


def extra_sided_families() -> set[str]:
    """resolve_sided_result() 로만 등장하는 확장 계열. FAMILIES 에는 없다."""
    literal = set(re.findall(r'resolve_sided_result\(\s*"([A-Z0-9_]+)"', source()))
    dynamic = {
        f"DOOR_BELT_MOLDING_{direction}"
        for direction in DIRECTIONS
        if re.search(r'f"DOOR_BELT_MOLDING_\{dbm_dir\}"', source())
    }
    return literal | dynamic


def emittable_codes() -> set[str]:
    """DB(`part_name_mapping`)에 실제로 들어갈 수 있는 코드.

    seed 생성 계약상 DB 에 들어가는 상태는 MAPPED 와 MAPPED_EXTENDED 뿐이다.
    MAPPED_SPLIT(`_L,_R`)과 MAPPED_SIDE_UNKNOWN(`_UNKNOWN`)은 제외된다.
    """
    codes: set[str] = set()
    for family, sided in families():
        codes.update({f"{family}_L", f"{family}_R"} if sided else {family})
    for family in extra_sided_families():
        codes.update({f"{family}_L", f"{family}_R"})
    codes.update(re.findall(r'"standard_code":\s*"([A-Z0-9_]+)"', source()))
    codes.update(f"PARKING_SENSOR_{direction}" for direction in DIRECTIONS)
    return codes


def seed_codes() -> set[str]:
    rows = re.findall(
        r"\('([A-Z0-9_]+)'\s*,\s*'(?:[^']|'')*'\s*,\s*'[A-Z_]+'\s*,\s*\d+\s*,\s*(?:TRUE|FALSE)\)",
        PART_CODE_SEED.read_text(encoding="utf-8"))
    return set(rows)


class PipelinePartCodeSubsetTest(unittest.TestCase):

    def test_seed_has_the_expected_shape(self):
        """유도가 아니라 대조 대상 자체가 비어 있으면 아래 테스트가 공허해진다."""
        self.assertEqual(len(seed_codes()), 56)

    def test_no_unknown_standard_code_producer(self):
        """소스에 새 코드 생성 지점이 생기면 먼저 여기서 잡는다."""
        self.assertEqual(standard_code_producers(), KNOWN_PRODUCERS)

    def test_pipeline_codes_are_a_subset_of_the_seed(self):
        missing = sorted(emittable_codes() - seed_codes())
        self.assertEqual(missing, [], f"seed 에 없는 파이프라인 코드: {missing}")

    def test_derivation_is_not_vacuous(self):
        """정규식이 하나도 못 잡으면 부분집합 검사는 항상 통과한다."""
        self.assertGreater(len(emittable_codes()), 30)


if __name__ == "__main__":
    unittest.main()
