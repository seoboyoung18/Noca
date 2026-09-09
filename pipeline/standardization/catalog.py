"""Canonical labels shared by labeling data, model output, and estimates."""

PARTS = {
    "FRONT_BUMPER": ("Front bumper", "앞 범퍼", "BUMPER", "CENTER"),
    "REAR_BUMPER": ("Rear bumper", "뒤 범퍼", "BUMPER", "CENTER"),
    "BONNET": ("Bonnet", "보닛", "BODY_PANEL", "CENTER"),
    "TRUNK_LID": ("Trunk lid", "트렁크 리드", "BODY_PANEL", "CENTER"),
    "ROOF": ("Roof", "루프", "BODY_PANEL", "CENTER"),
    "WINDSHIELD": ("Windshield", "앞 유리", "GLASS", "CENTER"),
    "REAR_WINDSHIELD": ("Rear windshield", "뒤 유리", "GLASS", "CENTER"),
    "UNDERCARRIAGE": ("Undercarriage", "차량 하부", "UNDERBODY", "CENTER"),
}

_SIDED_PARTS = {
    "A_PILLAR": ("A pillar", "A 필러", "BODY_PANEL"),
    "C_PILLAR": ("C pillar", "C 필러", "BODY_PANEL"),
    "FRONT_DOOR": ("Front door", "앞 도어", "BODY_PANEL"),
    "REAR_DOOR": ("Rear door", "뒤 도어", "BODY_PANEL"),
    "FRONT_FENDER": ("Front fender", "앞 펜더", "BODY_PANEL"),
    "REAR_FENDER": ("Rear fender", "뒤 펜더", "BODY_PANEL"),
    "FRONT_WHEEL": ("Front Wheel", "앞 휠", "WHEEL",),
    "REAR_WHEEL": ("Rear Wheel", "뒤 휠", "WHEEL",),
    "HEAD_LIGHT": ("Head lights", "헤드램프", "LAMP"),
    "REAR_LAMP": ("Rear lamp", "리어램프", "LAMP"),
    "ROCKER_PANEL": ("Rocker panel", "로커 패널", "BODY_PANEL"),
    "SIDE_MIRROR": ("Side mirror", "사이드미러", "MIRROR"),
}
for code, (raw, ko, group) in _SIDED_PARTS.items():
    for suffix, side in (("L", "LEFT"), ("R", "RIGHT")):
        PARTS[f"{code}_{suffix}"] = (f"{raw}({suffix})", f"{ko}({suffix})", group, side)

DAMAGES = {
    "SCRATCHED": ("Scratched", "긁힘"),
    "SEPARATED": ("Separated", "이격"),
    "CRUSHED": ("Crushed", "찌그러짐"),
    "BREAKAGE": ("Breakage", "파손"),
}

WORKS = {
    "COATING": ("coating", "도장"),
    "REPAIR": ("repair", "수리"),
    "SHEET_METAL": ("sheet_metal", "판금"),
    "EXCHANGE": ("exchange", "교환"),
}

# 임시 추천 규칙이다. 실제 작업 확정값이 아니라 견적 후보 생성에만 사용한다.
DEFAULT_WORK_BY_DAMAGE = {
    "SCRATCHED": ("COATING",),
    "SEPARATED": ("REPAIR", "EXCHANGE"),
    "CRUSHED": ("SHEET_METAL", "EXCHANGE"),
    "BREAKAGE": ("REPAIR", "EXCHANGE"),
}

# ---------------------------------------------------------------------------
# 견적서 실제 작업 어휘.
#
# 위 WORKS 4종은 사진 손상에서 만든 **작업 후보** 규칙이고, 아래는 견적서에
# 실제로 기재된 작업이다. 두 어휘를 같은 필드나 의미로 섞지 않는다.
#
# category
#   WORK      — 공임이 붙는 수리 작업
#   ANCILLARY — 수리 작업이 아닌 부대 비용(견인·구난). `탁송`은 `작업` 필드 값이
#               아니다 — 원천 1,716,713행에 `작업=탁송`은 0건이고, `탁송비`는
#               `작업항목 및 부품명`으로 나타나며 그 행의 `작업`은 견인 또는 구난이다.
#   STATUS    — 작업 유형이 아니라 손해사정 상태. 원천이 `작업` 필드를 덮어써
#               원래 작업 유형은 복구할 수 없다.
#
# 값: code -> (원문 대표 표기, 한글 표시명, category)
ESTIMATE_WORKS = {
    "COATING": ("도장", "도장", "WORK"),
    "REPAIR": ("수리", "수리", "WORK"),
    "SHEET_METAL": ("판금", "판금", "WORK"),
    "EXCHANGE": ("교환", "교환", "WORK"),
    "REMOVE_INSTALL": ("탈착", "탈부착", "WORK"),
    "OVERHAUL": ("오버홀", "오버홀", "WORK"),
    "OVERHAUL_HALF": ("1/2OH", "1/2 오버홀", "WORK"),
    "OVERHAUL_THIRD": ("1/3OH", "1/3 오버홀", "WORK"),
    "OVERHAUL_QUARTER": ("1/4OH", "1/4 오버홀", "WORK"),
    "ADJUSTMENT": ("조정", "조정", "WORK"),
    "TOWING": ("견인", "견인", "ANCILLARY"),
    "RESCUE": ("구난", "구난", "ANCILLARY"),
    "NOT_APPROVED": ("불인정", "불인정", "STATUS"),
}

# 원문 표기 흔들림. 대표 표기 외에 실제로 나타나는 별칭만 등록한다.
ESTIMATE_WORK_ALIASES = {
    "1/2오버홀": "OVERHAUL_HALF",
    "1/3오버홀": "OVERHAUL_THIRD",
    "1/4오버홀": "OVERHAUL_QUARTER",
    "견인비": "TOWING",
    "구난비": "RESCUE",
}
