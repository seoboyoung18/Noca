"""견적서 자유 텍스트 차량명칭을 차량 마스터(vehicle_model)의 표준 모델명으로 잇는다.

원천 `차량정보.차량명칭`은 연식·트림·마케팅 수식어가 섞인 자유 텍스트다.
(예: '아반떼AD(16)' · 'HG그랜져' · '올 뉴 모닝(11)' · 'LF 쏘나타 뉴라이즈')

정규화 규칙과 별칭표는 AI Hub 견적서 125,006건 전수 조사에서 만들었다.
근거와 한계는 ``Docs/Erd/A307_VEHICLE_AXIS.md`` 를 본다.

`차량정보.모델` 은 차종이 아니라 트림('1.6 GDI 스마트')이므로 쓰지 않는다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping

ALIAS_PATH = Path(__file__).with_name("vehicle_name_alias.json")

_HANGUL = re.compile(r"[가-힣]")
_TOKEN = re.compile(r"[A-Za-z0-9]+")
# 'E클래스' · '5시리즈' 앞 글자는 세대코드가 아니라 모델명의 일부다
_KEEP = re.compile(r"([A-Za-z]{1,3})(클래스|시리즈)")

_MARKET = ("올뉴", "올 뉴", "ALLNEW", "ALL NEW", "더넥스트", "더 넥스트",
           "넥스트", "더뉴", "더 뉴", "뉴라이즈", "디올뉴", "디 올 뉴")
_BRAND = ("NEW ", "벤츠", "BMW", "아우디", "폭스바겐", "도요타", "토요타",
          "혼다", "HONDA", "닛산", "포드", "볼보", "재규어", "링컨", "크라이슬러")
_SPELLING = (("그랜져", "그랜저"), ("렉스톤", "렉스턴"))

# 원천 '제작사/차종' 은 제조사와 차종구분이 한 칸에 섞여 있다 ('현대 / RV')
_MAKER = {
    "한국GM": "쉐보레", "쌍용": "KG모빌리티", "르노삼성": "르노코리아",
    "BENZ": "벤츠", "VOLKSWAGEN": "폭스바겐", "AUDI": "아우디",
    "TOYOTA": "도요타", "HONDA": "혼다", "NISSAN": "닛산", "FORD": "포드",
    "CHRYSLER": "크라이슬러", "JAGUAR": "재규어", "LINCOLN": "링컨", "VOLVO": "볼보",
}
_BODY = {"승용", "RV"}


class VehicleNameError(ValueError):
    """차량명칭을 해석할 수 없을 때."""


@dataclass(frozen=True)
class VehicleRef:
    """원천 차량명칭 한 건의 해석 결과."""

    raw: str
    normalized: str
    model_name: str | None       # vehicle_model.model_name. 마스터에 없으면 None
    price_tier: str | None       # P1~P4. 표본이 부족하면 None
    price_index: float | None    # 1.00 = 전체 중앙값

    @property
    def resolved(self) -> bool:
        return self.model_name is not None


def normalize_vehicle_name(raw: str | None) -> str:
    """자유 텍스트 차량명칭을 비교 가능한 형태로 줄인다.

    >>> normalize_vehicle_name("아반떼AD(16)")
    '아반떼'
    >>> normalize_vehicle_name("올 뉴 모닝(11)")
    '모닝'
    >>> normalize_vehicle_name("벤츠 E클래스")
    'E클래스'
    >>> normalize_vehicle_name("뉴SM3(2012)")
    'SM3'
    """
    text = (raw or "").strip()
    if not text:
        return ""
    text = re.sub(r"\([^)]*\)", "", text)          # 연식·도어수·별칭
    for old, new in _SPELLING:
        text = text.replace(old, new)
    for word in _MARKET:
        text = text.replace(word, "")
    for brand in _BRAND:                            # 브랜드 접두어
        head = brand.strip()
        if text.strip().upper().startswith(head.upper()) and len(text.strip()) > len(head):
            text = text.strip()[len(head):]
            break
    if text.lstrip().startswith("뉴") and len(text.strip()) > 2:
        text = text.strip()[1:]

    text = _KEEP.sub(lambda m: "\x00" + m.group(1) + "\x01" + m.group(2), text)
    if _HANGUL.search(text.replace("\x00", "").replace("\x01", "")):
        body = re.sub(r"\x00[^\x01]*\x01", "", text)
        # 영문자를 포함한 토큰만 세대코드로 본다. '5시리즈'의 5 는 남는다
        stripped = _TOKEN.sub(
            lambda m: "" if re.search(r"[A-Za-z]", m.group()) else m.group(), body)
        kept = "".join(re.findall(r"\x00([^\x01]*)\x01", text))
        merged = (kept + stripped) if kept else stripped
        if len(re.sub(r"[\s\-]+", "", merged)) >= 2:   # 한글만 남겨 이름이 되는 경우만
            text = merged
    text = text.replace("\x00", "").replace("\x01", "")
    return re.sub(r"[\s\-]+", "", text).strip()


def split_manufacturer(raw: str | None) -> tuple[str | None, str | None]:
    """'현대 / RV' → ('현대', 'RV'). 차종구분이 없으면 None.

    >>> split_manufacturer("현대 / RV")
    ('현대', 'RV')
    >>> split_manufacturer("한국GM")
    ('쉐보레', None)
    """
    text = (raw or "").strip()
    if not text:
        return None, None
    parts = [p.strip() for p in text.split("/")]
    maker = _MAKER.get(parts[0], parts[0]) or None
    body = parts[1] if len(parts) > 1 and parts[1] in _BODY else None
    return maker, body


@lru_cache(maxsize=1)
def _alias() -> dict[str, Any]:
    with ALIAS_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def resolve_vehicle(raw: str | None) -> VehicleRef:
    """원천 차량명칭을 표준 모델명·가격대로 해석한다.

    별칭표에 있으면 그 값을 쓰고, 없으면 정규화 결과로 한 번 더 찾는다.
    마스터에 없는 차종이면 ``model_name`` 이 None 이다 — 오류가 아니다.
    """
    table = _alias()
    raw_text = (raw or "").strip()
    normalized = normalize_vehicle_name(raw_text)
    entry = table["alias"].get(raw_text)
    if entry is None:
        for key, value in table["alias"].items():          # 표기가 처음 보는 경우
            if value.get("model_name") and normalize_vehicle_name(key) == normalized:
                entry = value
                break
    if entry is None:
        tier = table["model_price_tier"].get(normalized)
        return VehicleRef(raw_text, normalized, None, tier, None)
    model_name = entry.get("model_name")
    tier = entry.get("price_tier") or table["model_price_tier"].get(model_name or "")
    return VehicleRef(raw_text, normalized, model_name, tier, entry.get("price_index"))


def price_tier_of_model(model_name: str | None) -> str | None:
    """마스터 모델명의 대표 가격대. 신규 등록 차량의 기본값을 정할 때 쓴다."""
    if not model_name:
        return None
    return _alias()["model_price_tier"].get(model_name)


def alias_version() -> str:
    return _alias()["version"]


def from_estimate(vehicle: Mapping[str, Any] | None) -> VehicleRef:
    """견적서 ``차량정보`` 블록에서 바로 해석한다."""
    return resolve_vehicle((vehicle or {}).get("차량명칭"))
