"""견적서 원본 부품명을 표준 부위 코드로 매핑한다.

입력은 extract_estimate_labels.py가 만든 audit JSON이다. YOLO 32종 표준
부품과 견적 전용 확장 코드로 자동 매핑하고, 판단이 필요한 항목은
REVIEW_CONFLICT / OUT_OF_SCOPE_PART로 분류해 남긴다.
"""

import argparse
import json
import re
import unicodedata
from pathlib import Path

_parser = argparse.ArgumentParser()
_parser.add_argument("--audit-json", type=Path, required=True,
                     help="extract_estimate_labels.py 산출물")
_parser.add_argument("--output", type=Path, required=True,
                     help="매핑 결과 JSON 산출 경로")
_args = _parser.parse_args()

input_path = _args.audit_json
output_path = _args.output
data = json.loads(input_path.read_text(encoding="utf-8"))


def compact(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).lower()
    return re.sub(r"[\s_\-－·.,/]+", "", value)


# "5도어"/"3도어형" is a body-style descriptor (5-door hatchback, etc.), never a
# reference to an actual door part. Left in place it makes FRONT_DOOR/REAR_DOOR
# fire on rows like "뒤휀다(우,5도어,연료주입구" (a rear-fender row) purely because
# "뒤" and "도어" happen to land within the family pattern's character gap.
BODY_STYLE_DOOR_RE = re.compile(r"\d+도어형?")

BOTH_SIDES_RE = re.compile(r"좌우|우좌")
LEFT_RE = re.compile(r"좌(?:측|[\s,()/\-.]|$)|\(\s*l\s*\)|left")
# "우" alone/attached is only trusted as a direction marker when it is not the
# tail of an unrelated word ending in 우 — "윈도우"(window), "…하우(징)", "…아우(터)"
# truncated mid-word, or "…로우" (Low 트림/등화 단수 표기, e.g. "도어 로우", "범퍼 로우").
# A properly delimited "…-우"/"…,우"/"…우측" still matches.
RIGHT_RE = re.compile(r"(?<![하아도로])우(?:측|[\s,()/\-.]|$)|\(\s*r\s*\)|right")


def side_of(value: str):
    text = unicodedata.normalize("NFKC", value).lower()
    if BOTH_SIDES_RE.search(text):
        return "MULTIPLE"
    left = bool(LEFT_RE.search(text))
    right = bool(RIGHT_RE.search(text))
    if left and right:
        return "MULTIPLE"
    if left:
        return "L"
    if right:
        return "R"
    return None


# Each sided/directional family lists its two forms in a fixed order:
# patterns[0] = "modifier BEFORE the anchor word" (e.g. "리어 도어") — the normal
#   Korean determiner-then-noun order, treated as the family's true identity.
# patterns[1] = "anchor BEFORE modifier" (e.g. "도어 ... 리어") — usually describes
#   a sub-position/edge of the part named just before it, not a different part
#   (e.g. "리어 도어 프론트" = the REAR door's front-edge trim, not a front door).
# Only these four pairs use that convention consistently; families defined with
# a single fixed pattern (BONNET, HEAD_LIGHT, ...) have no such ambiguity.
BEFORE_AFTER_FAMILIES = {"FRONT_BUMPER", "REAR_BUMPER", "FRONT_DOOR", "REAR_DOOR", "FRONT_FENDER", "REAR_FENDER", "FRONT_WHEEL", "REAR_WHEEL"}

FAMILIES = [
    ("FRONT_BUMPER", "앞 범퍼", False, [r"(앞|전면|프런트|프론트|후론트|front).*범퍼", r"범퍼.*(앞|전면|프런트|프론트|후론트|front)"]),
    ("REAR_BUMPER", "뒤 범퍼", False, [r"(뒤|후면|리어|rear).*범퍼", r"범퍼.*(뒤|후면|리어|rear)"]),
    ("BONNET", "보닛", False, [r"보닛|본넷|본네트|후드|hood"]),
    ("TRUNK_LID", "트렁크 리드", False, [r"트렁크|트렁크리드|테일게이트|백도어|tailgate|trunklid"]),
    ("ROOF", "루프", False, [r"루프|지붕|roof"]),
    ("REAR_WINDSHIELD", "뒤 유리", False, [r"(리어|뒤|후면|rear).*(윈드실드|윈드쉴드|유리|글라스)", r"(윈드실드|윈드쉴드).*(리어|뒤|후면|rear)"]),
    ("WINDSHIELD", "앞 유리", False, [r"(프런트|프론트|앞|전면|front).*(윈드실드|윈드쉴드|유리|글라스)", r"윈드실드|윈드쉴드"]),
    ("A_PILLAR", "A 필러", True, [r"a필러|에이필러|apillar"]),
    ("C_PILLAR", "C 필러", True, [r"c필러|씨필러|cpillar"]),
    ("FRONT_DOOR", "앞 도어", True, [r"(앞|프런트|프론트|후론트|front).{0,8}(도어|door)", r"(도어|door).{0,8}(앞|프런트|프론트|후론트|front)"]),
    ("REAR_DOOR", "뒤 도어", True, [r"(뒤|후면|리어|rear).{0,8}(도어|door)", r"(도어|door).{0,8}(뒤|후면|리어|rear)"]),
    ("FRONT_FENDER", "앞 펜더", True, [r"(앞|프런트|프론트|후론트|front).{0,8}(휀다|휀더|펜더|펜다|fender)", r"(휀다|휀더|펜더|펜다|fender).{0,8}(앞|프런트|프론트|후론트|front)"]),
    ("REAR_FENDER", "뒤 펜더", True, [r"(뒤|후면|리어|rear).{0,8}(휀다|휀더|펜더|펜다|쿼터패널|쿼터판넬|fender|quarterpanel)", r"(휀다|휀더|펜더|펜다|쿼터패널|쿼터판넬|fender|quarterpanel).{0,8}(뒤|후면|리어|rear)"]),
    ("HEAD_LIGHT", "헤드램프", True, [r"헤드램프|헤드라이트|전조등|headlamp|headlight"]),
    ("REAR_LAMP", "리어램프", True, [r"리어.*(램프|라이트)|테일램프|후미등|컴비네이션램프|rearlamp|taillamp"]),
    ("FRONT_WHEEL", "앞 휠", True, [r"(앞|전면|프런트|프론트|후론트|front).*(휠|wheel)", r"(휠|wheel).*(앞|전면|프런트|프론트|후론트|front)"]),
    ("REAR_WHEEL", "뒤 휠", True, [r"(뒤|후면|리어|rear).*(휠|wheel)", r"(휠|wheel).*(뒤|후면|리어|rear)"]),
    ("ROCKER_PANEL", "로커 패널", True, [r"로커패널|로커판넬|사이드실|rockerpanel|sidesill"]),
    ("SIDE_MIRROR", "사이드미러", True, [r"사이드미러|아웃사이드미러|도어미러|백미러|아웃사이드.{0,4}리어뷰|sidemirror|outsidemirror"]),
    ("UNDERCARRIAGE", "차량 하부", False, [r"언더커버|언더바디|차량하부|하체|undercarriage|undercover|underbody"]),
]


def non_part_cost(raw_name: str):
    """Classify cost/service rows that should never receive a YOLO part code."""
    text = compact(raw_name)
    rules = [
        (r"공통시간작업|도장공통시간", "PAINT_OVERHEAD", "PAINT_COMMON_TIME", "도장 공통 작업", "도장 공통 공정의 시간·공임 항목"),
        (r"가열건조", "PAINT_OVERHEAD", "PAINT_HEAT_DRYING", "도장 가열건조비", "도장 후 열처리 부스 가동 비용"),
        (r"컬러매칭|색상매칭|색맞춤", "PAINT_OVERHEAD", "PAINT_COLOR_MATCHING", "도장 컬러 매칭", "도장 색상 확인·조색 관련 비용"),
        (r"수용성.*(도장|추가|청구)|도장.*수용성", "PAINT_OVERHEAD", "PAINT_MATERIAL_SURCHARGE", "수용성 도장 추가비", "수용성 도장 재료·공정의 추가 비용"),
        (r"보카시|투톤|폴리싱", "PAINT_OVERHEAD", "PAINT_FINISHING", "도장 마감·할증 작업", "도장 마감 또는 할증 비용"),
        (r"도장재료", "PAINT_OVERHEAD", "PAINT_MATERIAL", "도장 재료비", "도장 재료 관련 비용"),
        (r"^(견인|견인비)$", "SERVICE", "TOWING_SERVICE", "견인 서비스", "부품이 아닌 견인 서비스 항목"),
        (r"^(구난|구난비)$", "SERVICE", "RESCUE_SERVICE", "구난 서비스", "부품이 아닌 구난 서비스 항목"),
        (r"휠얼라인먼트", "SERVICE", "WHEEL_ALIGNMENT", "휠 얼라인먼트", "부품이 아닌 휠 정렬 작업"),
        (r"탁송", "SERVICE", "DELIVERY_SERVICE", "탁송 서비스", "부품이 아닌 차량 탁송(운송) 서비스 비용"),
    ]
    for pattern, item_type, cost_category, name, reason in rules:
        if re.search(pattern, text):
            return {
                "item_type": item_type,
                "cost_category": cost_category,
                "standard_code": "",
                "standard_name": name,
                "status": "NOT_A_PART",
                "reason": reason,
            }
    return None


# ---------------------------------------------------------------------------
# Estimate-only extended codes: real, frequently repaired parts that sit
# outside the YOLO 32. Each is checked eagerly (before the 32-family loop)
# because it is a strict specialization of one or two of the 32 keywords —
# left to the generic loop it would just turn into a REVIEW_CONFLICT between
# its component keywords instead of resolving to the more specific part.
# ---------------------------------------------------------------------------

GRILLE_RADIATOR_RE = re.compile(r"라디에(?:이터|터|타|이트)")
GRILLE_RE = re.compile(r"그릴")
GRILLE_SUBPART_RE = re.compile(r"브라켓|가니쉬|메쉬|크롬|카메라")


def radiator_grille_match(text: str):
    """Returns "CORE" for the grille panel itself and its accepted variants
    (including the upper cover, per the dashboard's stated decision to fold
    it in), "SUBPART" for accessories mounted on/at the grille (bracket,
    garnish, mesh insert, chrome piece, the front camera housing) that stay
    held back for a human decision, or None when no radiator+grille keyword
    combination is present at all."""
    if not (GRILLE_RADIATOR_RE.search(text) and GRILLE_RE.search(text)):
        return None
    if GRILLE_SUBPART_RE.search(text):
        return "SUBPART"
    return "CORE"


QUARTER_GLASS_RE = re.compile(r"(휀다|휀더|펜더|펜다).{0,4}고정유리|고정유리.{0,4}(휀다|휀더|펜더|펜다)")


def quarter_glass_match(text: str) -> bool:
    """The small fixed (non-opening) glass bonded near the rear fender —
    "고정유리" is specific enough on its own that it never appears for the
    ordinary rear windshield or a plain fender panel."""
    return bool(QUARTER_GLASS_RE.search(text))


def rear_bumper_undercover_match(text: str) -> bool:
    """Rear bumper's lower under-cover/skirt panel. Both "리어범퍼" and
    "언더커버" are direct (non-parenthetical) keyword hits for their own
    32-part families, so this needs its own precedence rule rather than
    Rule A/B below."""
    has_bumper = any(keyword in text for keyword in ("리어범퍼", "뒤범퍼", "뒷범퍼"))
    return has_bumper and "언더커버" in text


REAR_BUMPER_LAMP_RE = re.compile(r"백업램프|포그램프|컴비램프|콤비램프")
REAR_BUMPER_LAMP_SUBPART_RE = re.compile(r"브라켓")


def rear_bumper_lamp_match(text: str) -> bool:
    """Lamp built into the rear bumper (backup lamp, fog lamp, combination
    lamp) — "리어범퍼" and the lamp keyword are both direct keyword hits for
    their own 32-part families (REAR_BUMPER / REAR_LAMP), so like
    rear_bumper_undercover_match this needs its own precedence rule rather
    than Rule A/B. Brackets/hardware mounting the lamp (예: "리어범퍼 컴비램프
    브라켓") are excluded — per the same "두 부품을 실제로 연결하는 부속품은
    사람이 결정" policy as the grille SUBPART carve-out, they're left for
    REVIEW_CONFLICT rather than folded in automatically."""
    has_bumper = any(keyword in text for keyword in ("리어범퍼", "뒤범퍼", "뒷범퍼"))
    if not has_bumper or not REAR_BUMPER_LAMP_RE.search(text):
        return False
    return not REAR_BUMPER_LAMP_SUBPART_RE.search(text)


# ---------------------------------------------------------------------------
# Round 2 of estimate-only extended codes: additional real, frequently
# repaired parts pulled out of OUT_OF_SCOPE_PART by volume (same "text-only
# standardization, no YOLO class implied" treatment as RADIATOR_GRILLE).
# Each is only ever consulted from the `not unique` gate in map_name (i.e.
# only when none of the YOLO 32 families already matched), so none of these
# can ever steal a row from a real 32-class match.
# ---------------------------------------------------------------------------

# Shared front/rear direction words, matching the same alternation the 32
# YOLO families already use (앞|전면|프런트|프론트|후론트|후런트|front for
# front; 뒤|후면|리어|rear for rear) — kept as one shared pair so every
# extended-code direction check stays consistent with it, rather than each
# function inventing its own narrower word list.
FRONT_WORD_RE = re.compile(r"앞|전방|전면|프런트|프론트|후론트|후런트|front")
REAR_WORD_RE = re.compile(r"뒤|후방|후면|리어|rear")

PARKING_SENSOR_RE = re.compile(r"감지센서|백워닝")


def parking_sensor_match(text: str):
    """Ultrasonic parking-assist sensor (bumper-mounted, no L/R — only a
    front/rear position). "전방" reads as front, "후방" or "백워닝" ("back
    warning", the common industry synonym for the rear sensor) as rear.
    Returns None — falls through to the safe OUT_OF_SCOPE fallback — for the
    small residual with neither marker, rather than guessing a side."""
    if not PARKING_SENSOR_RE.search(text):
        return None
    front = bool(FRONT_WORD_RE.search(text))
    rear = bool(REAR_WORD_RE.search(text)) or "백워닝" in text
    if front and not rear:
        return "FRONT"
    if rear and not front:
        return "REAR"
    return None


FOG_LAMP_RE = re.compile(r"포그램프")


def fog_lamp_match(text: str) -> bool:
    """Fog lamp housing, cover, bezel and blanking-cover variants — all the
    same front-corner assembly, sided L/R like the 32-class lamps."""
    return bool(FOG_LAMP_RE.search(text))


SIDE_STEP_RE = re.compile(r"사이드스텝")


def side_step_match(text: str) -> bool:
    """Running-board / side-step molding or panel (distinct from the 32-class
    ROCKER_PANEL/사이드실 — a different, unibody-integrated part)."""
    return bool(SIDE_STEP_RE.search(text))


BACK_PANEL_RE = re.compile(r"백패널|리어패널")
BACK_PANEL_EXCLUDE_RE = re.compile(r"스마트키|안테나")


def back_panel_match(text: str) -> bool:
    """Rear body / trunk-floor cross panel ("백패널"/"리어패널"), a single
    non-sided part. Excludes rows where 백패널 is only a location note for
    an unrelated attached part (e.g. a smart-key antenna module mounted at
    the back panel) rather than the panel's own identity."""
    if not BACK_PANEL_RE.search(text):
        return False
    return not BACK_PANEL_EXCLUDE_RE.search(text)


COWL_GRILLE_RE = re.compile(r"카울그릴")


def cowl_grille_match(text: str) -> bool:
    """Cowl grille/vent panel below the windshield wipers — a single
    non-sided part, unrelated to RADIATOR_GRILLE."""
    return bool(COWL_GRILLE_RE.search(text))


WASHER_TANK_RE = re.compile(r"와셔탱크")


def washer_tank_match(text: str) -> bool:
    """Washer fluid reservoir tank — a single non-sided part (the rare
    "(리어)" rear-washer variant is folded into the same code, negligible
    volume)."""
    return bool(WASHER_TANK_RE.search(text))


EMBLEM_RE = re.compile(r"엠블램|엠블럼|앰블럼|앰블램")


def emblem_match(text: str) -> bool:
    """Maker/model badge (HYUNDAI, KIA, model-name plates, trim badges) — a
    single non-sided part. The rare left/right variant (e.g. a "블루 드라이브"
    fender badge pair) is folded into the same code, negligible volume."""
    return bool(EMBLEM_RE.search(text))


FRONT_PANEL_RE = re.compile(r"후론트패널|프런트패널|프론트패널")
FRONT_PANEL_EXCLUDE_RE = re.compile(r"배선|적재함")


def front_panel_match(text: str) -> bool:
    """Front-end structural panel / radiator support assembly (behind the
    bumper, carries the radiator and headlight mounts) — a single non-sided
    part, distinct from BONNET and FRONT_BUMPER. Excludes rows where
    "후론트패널" only locates an unrelated wiring harness ("프런트패널배선") or
    a pickup-truck cargo-bed front panel ("적재함") rather than the panel's
    own identity."""
    if not FRONT_PANEL_RE.search(text):
        return False
    return not FRONT_PANEL_EXCLUDE_RE.search(text)


REAR_CAMERA_RE = re.compile(r"후방카메라")


def rear_camera_match(text: str) -> bool:
    """Rear-view/backup camera — a single non-sided part."""
    return bool(REAR_CAMERA_RE.search(text))


SLIDING_DOOR_RE = re.compile(r"슬라이딩도어")


def sliding_door_match(text: str) -> bool:
    """Minivan/van sliding side door — sided L/R like the 32-class doors.
    Bundles the door panel itself with its rail, rail cover, trim, outside
    handle and belt molding (all inherent parts of the sliding-door
    mechanism, same bundling policy as FOG_LAMP's housing+cover+bezel)."""
    return bool(SLIDING_DOOR_RE.search(text))


DOOR_BELT_MOLDING_RE = re.compile(r"도어밸트|도어벨트")
DOOR_BELT_MOLDING_HAS_MOLDING_RE = re.compile(r"몰딩")


def door_belt_molding_match(text: str):
    """Door belt-line ("눈썹") molding — needs both a front/rear marker and,
    via resolve_sided_result downstream, a left/right marker to fully
    resolve. Returns None (safe OUT_OF_SCOPE fallback) when the front/rear
    marker itself is missing or ambiguous, rather than guessing."""
    if not (DOOR_BELT_MOLDING_RE.search(text) and DOOR_BELT_MOLDING_HAS_MOLDING_RE.search(text)):
        return None
    front = bool(FRONT_WORD_RE.search(text))
    rear = bool(REAR_WORD_RE.search(text))
    if front and not rear:
        return "FRONT"
    if rear and not front:
        return "REAR"
    return None


PAREN_RE = re.compile(r"\([^()]*\)")


def parenthetical_spans(text: str):
    return [(m.start(), m.end()) for m in PAREN_RE.finditer(text)]


def _within_any_span(start: int, end: int, spans) -> bool:
    return any(s <= start and end <= e for s, e in spans)


def match_strength(family: str, patterns, text: str, text_no_paren: str):
    """Classifies how a family matched a row, for conflict resolution. The
    key test is whether the family's match SURVIVES with every "(...)" span
    deleted from the text — deleting parens collapses a location/attachment
    note like "...(범퍼부착)" or "...(헤드램프부착)" cleanly, including cases
    where a greedy ".*" gap pattern (e.g. REAR_BUMPER's "(뒤|리어).*범퍼")
    starts outside the parens but only reaches its anchor keyword by reading
    through into one — a plain "does every hit's span sit inside a paren"
    check misses that overreach, since the match's start is outside.
    - "parenthetical": the family has no match at all once parens are
      removed — its only evidence is parenthetical/attachment text, not the
      part's own identity.
    - "before": survives with parens removed, and does so via patterns[0]
      ("modifier before the anchor word") — the strong, normal-word-order
      form. Only meaningful for BEFORE_AFTER_FAMILIES; treated the same as
      "direct" otherwise.
    - "after": survives with parens removed, but only via patterns[1]
      ("anchor before modifier") — a weaker, position-describing form.
    - "direct": survives with parens removed, no before/after distinction
      available for this family.
    Returns None if the family did not match the original text at all.
    """
    hits = [m for pattern in patterns for m in re.finditer(pattern, text)]
    if not hits:
        return None
    hits_no_paren = [m for pattern in patterns for m in re.finditer(pattern, text_no_paren)]
    if not hits_no_paren:
        return "parenthetical"
    if family in BEFORE_AFTER_FAMILIES:
        if re.search(patterns[0], text_no_paren):
            return "before"
        return "after"
    return "direct"


def resolve_conflict(candidates):
    """candidates: list of (family, name_ko, sided, strength). Applies, in
    order: (Rule A) drop matches whose only evidence is parenthetical, when
    at least one other candidate has real evidence; (Rule B) prefer a
    "before"-strength match over "after"-only ones, but ONLY when every
    remaining candidate belongs to BEFORE_AFTER_FAMILIES — i.e. Rule B is
    strictly for arbitrating a FRONT_X vs REAR_X sibling pair (both read the
    same X.*Y / Y.*X gap patterns), never for picking between an unrelated
    family (e.g. REAR_LAMP) and a BEFORE_AFTER family whose greedy ".*" gap
    pattern happens to reach a stray "before"-position hit (e.g. "리어" at
    the start of "리어컴비네이션램프(범퍼부착)" reading all the way through to
    "범퍼" inside the parens as a spurious REAR_BUMPER "before" match).
    Returns the resolved single candidate, or None if still ambiguous
    (2+ candidates) or empty."""
    non_paren = [c for c in candidates if c[3] != "parenthetical"]
    if len(non_paren) == 1:
        return non_paren[0]
    if non_paren:
        candidates = non_paren
    if len(candidates) <= 1:
        return candidates[0] if candidates else None
    if all(c[0] in BEFORE_AFTER_FAMILIES for c in candidates):
        before = [c for c in candidates if c[3] == "before"]
        after_or_direct = [c for c in candidates if c[3] != "before"]
        if before and not after_or_direct:
            return None if len(before) > 1 else before[0]
        if len(before) == 1 and after_or_direct:
            return before[0]
    return None


def resolve_sided_result(family: str, name_ko: str, raw_name: str, extended: bool):
    ext = "_EXTENDED" if extended else ""
    side = side_of(raw_name)
    if side == "MULTIPLE":
        return {
            "item_type": "PART", "cost_category": "VEHICLE_PART",
            "standard_code": f"{family}_L,{family}_R",
            "standard_name": f"{name_ko}(좌+우 동시)",
            "status": "MAPPED_SPLIT",
            "reason": "한 항목에 좌·우가 함께 표기됨 — 적재 시 좌/우 두 행으로 나누고 원본 항목당 비용은 절반씩 배분 권장",
        }
    if side is None:
        return {
            "item_type": "PART", "cost_category": "VEHICLE_PART",
            "standard_code": f"{family}_UNKNOWN",
            "standard_name": f"{name_ko}(방향 미상)",
            "status": f"MAPPED{ext}_SIDE_UNKNOWN",
            "reason": "표준 코드는 좌·우 구분이 필요하지만 원본명에서 방향을 확정할 수 없음 — 수리방법·비용 통계에는 포함하고, 좌우 구분이 필요한 기능(사진 매칭 등)에서는 제외 권장",
        }
    return {
        "item_type": "PART", "cost_category": "VEHICLE_PART",
        "standard_code": f"{family}_{side}",
        "standard_name": f"{name_ko}({side})",
        "status": f"MAPPED{ext}",
        "reason": "부품명과 방향 패턴 일치",
    }


def map_name(raw_name: str):
    classified = non_part_cost(raw_name)
    if classified:
        return classified
    text = compact(raw_name)
    text = BODY_STYLE_DOOR_RE.sub("", text)

    if quarter_glass_match(text):
        return resolve_sided_result("QUARTER_GLASS", "뒤 쿼터글라스", raw_name, extended=True)
    if rear_bumper_undercover_match(text):
        return {
            "item_type": "PART", "cost_category": "VEHICLE_PART",
            "standard_code": "REAR_BUMPER_UNDERCOVER",
            "standard_name": "뒤 범퍼 언더커버",
            "status": "MAPPED_EXTENDED",
            "reason": "리어범퍼 언더커버 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (좌/우/센터를 하나로 묶음, 부모 코드인 REAR_BUMPER처럼 비sided)",
        }
    if rear_bumper_lamp_match(text):
        return resolve_sided_result("REAR_BUMPER_LAMP", "뒤 범퍼 램프(백업/포그/콤비)", raw_name, extended=True)

    text_no_paren = PAREN_RE.sub("", text)
    matches = []
    for family, name_ko, sided, patterns in FAMILIES:
        strength = match_strength(family, patterns, text, text_no_paren)
        if strength is not None:
            matches.append((family, name_ko, sided, strength))
    unique = []
    seen = set()
    for match in matches:
        if match[0] not in seen:
            seen.add(match[0])
            unique.append(match)

    if not unique:
        # None of the 32 YOLO-linked families matched. Only within this leftover
        # pool do we offer estimate-only extended codes (outside the YOLO 32),
        # so an extended code never overrides an already-correct 32-part match
        # (e.g. "후드 몰딩(라디에이터그릴 상부)" stays BONNET, not RADIATOR_GRILLE).
        grille = radiator_grille_match(text)
        if grille == "CORE":
            return {
                "item_type": "PART",
                "cost_category": "VEHICLE_PART",
                "standard_code": "RADIATOR_GRILLE",
                "standard_name": "라디에이터 그릴",
                "status": "MAPPED_EXTENDED",
                "reason": "라디에이터그릴 표기 변형 통합 — YOLO 32종 밖의 견적 전용 확장 코드",
            }
        sensor_dir = parking_sensor_match(text)
        if sensor_dir is not None:
            side_ko = "앞" if sensor_dir == "FRONT" else "뒤"
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": f"PARKING_SENSOR_{sensor_dir}",
                "standard_name": f"{side_ko} 주차 감지센서",
                "status": "MAPPED_EXTENDED",
                "reason": "초음파 주차 감지센서 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (전방/후방 구분, 좌우 구분 없음)",
            }

        if fog_lamp_match(text):
            return resolve_sided_result("FOG_LAMP", "포그램프", raw_name, extended=True)

        if side_step_match(text):
            return resolve_sided_result("SIDE_STEP", "사이드스텝", raw_name, extended=True)

        dbm_dir = door_belt_molding_match(text)
        if dbm_dir is not None:
            side_ko = "앞" if dbm_dir == "FRONT" else "뒤"
            return resolve_sided_result(
                f"DOOR_BELT_MOLDING_{dbm_dir}", f"{side_ko} 도어밸트(눈썹)몰딩", raw_name, extended=True
            )

        if back_panel_match(text):
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": "BACK_PANEL",
                "standard_name": "백패널(리어패널)",
                "status": "MAPPED_EXTENDED",
                "reason": "백패널/리어패널 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (비sided)",
            }

        if cowl_grille_match(text):
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": "COWL_GRILLE",
                "standard_name": "카울그릴",
                "status": "MAPPED_EXTENDED",
                "reason": "카울그릴 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (비sided)",
            }

        if washer_tank_match(text):
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": "WASHER_TANK",
                "standard_name": "와셔탱크",
                "status": "MAPPED_EXTENDED",
                "reason": "와셔탱크 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (비sided)",
            }

        if emblem_match(text):
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": "EMBLEM",
                "standard_name": "엠블램",
                "status": "MAPPED_EXTENDED",
                "reason": "엠블램/엠블럼 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (비sided)",
            }

        if front_panel_match(text):
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": "FRONT_PANEL",
                "standard_name": "프런트 패널",
                "status": "MAPPED_EXTENDED",
                "reason": "후론트/프런트 패널(라디에이터 서포트) 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (비sided)",
            }

        if rear_camera_match(text):
            return {
                "item_type": "PART", "cost_category": "VEHICLE_PART",
                "standard_code": "REAR_CAMERA",
                "standard_name": "후방카메라",
                "status": "MAPPED_EXTENDED",
                "reason": "후방카메라 표기 통합 — YOLO 32종 밖의 견적 전용 확장 코드 (비sided)",
            }

        if sliding_door_match(text):
            return resolve_sided_result("SLIDING_DOOR", "슬라이딩 도어", raw_name, extended=True)

        reason = "YOLO 32개 외장 부품 규칙과 일치하지 않음"
        if grille == "SUBPART":
            reason = "라디에이터그릴 부속품(브라켓/가니쉬/메쉬/크롬/카메라 등) 후보 — RADIATOR_GRILLE 편입 여부 확인 필요"
        return {"item_type": "OUT_OF_SCOPE_PART", "cost_category": "", "standard_code": "", "standard_name": "", "status": "OUT_OF_SCOPE_PART", "reason": reason}

    if len(unique) > 1:
        resolved = resolve_conflict(unique)
        if resolved is not None:
            unique = [resolved]

    if len(unique) > 1:
        return {"item_type": "PART", "cost_category": "VEHICLE_PART", "standard_code": "", "standard_name": " / ".join(x[1] for x in unique), "status": "REVIEW_CONFLICT", "reason": "둘 이상의 부품 계열과 일치"}
    family, name_ko, sided, _strength = unique[0]
    if not sided:
        return {"item_type": "PART", "cost_category": "VEHICLE_PART", "standard_code": family, "standard_name": name_ko, "status": "MAPPED", "reason": "부품명 패턴 일치"}
    return resolve_sided_result(family, name_ko, raw_name, extended=False)


mapped_rows = []
status_counts = {}
status_item_counts = {}
code_counts = {}
code_item_counts = {}
for row in data["rows"]:
    mapping = map_name(row["raw_name"])
    result = {**row, **mapping}
    mapped_rows.append(result)
    status = mapping["status"]
    status_counts[status] = status_counts.get(status, 0) + 1
    status_item_counts[status] = status_item_counts.get(status, 0) + row["count"]
    if mapping["standard_code"]:
        for code in mapping["standard_code"].split(","):
            code_counts[code] = code_counts.get(code, 0) + 1
            code_item_counts[code] = code_item_counts.get(code, 0) + row["count"]

WORK_MAP = {
    "도장": ("COATING", "도장"),
    "탈착": ("REMOVE_INSTALL", "탈부착"),
    "교환": ("EXCHANGE", "교환"),
    "판금": ("SHEET_METAL", "판금"),
    "수리": ("REPAIR", "수리"),
    "오버홀": ("OVERHAUL", "오버홀"),
    "1/2OH": ("OVERHAUL_HALF", "1/2 오버홀"),
    "1/3OH": ("OVERHAUL_THIRD", "1/3 오버홀"),
    "1/4OH": ("OVERHAUL_QUARTER", "1/4 오버홀"),
    "1/2오버홀": ("OVERHAUL_HALF", "1/2 오버홀"),
    "1/3오버홀": ("OVERHAUL_THIRD", "1/3 오버홀"),
    "1/4오버홀": ("OVERHAUL_QUARTER", "1/4 오버홀"),
    "조정": ("ADJUSTMENT", "조정"),
    "견인": ("TOWING", "견인"),
    "견인비": ("TOWING", "견인"),
    "구난": ("RESCUE", "구난"),
    "구난비": ("RESCUE", "구난"),
    "불인정": ("NOT_APPROVED", "불인정"),
}
work_rows = []
for raw_work, count in data["work_counts"]:
    code, name = WORK_MAP.get(raw_work, ("", ""))
    work_rows.append({"raw_work": raw_work, "count": count, "standard_code": code, "standard_name": name,
                      "status": "MAPPED" if code else "REVIEW"})

output = {
    "summary": {
        "file_counts": data["file_counts"],
        "item_count": data["item_count"],
        "unique_name_count": data["unique_name_count"],
        "status_counts": status_counts,
        "status_item_counts": status_item_counts,
        "code_counts": code_counts,
        "code_item_counts": code_item_counts,
    },
    "work_rows": work_rows,
    "rows": mapped_rows,
}
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps(output, ensure_ascii=False), encoding="utf-8")
print(json.dumps(output["summary"], ensure_ascii=False))
