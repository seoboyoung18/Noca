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

