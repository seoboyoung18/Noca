"""손상 ROI 생성 규칙 — 색인 경로와 추론 경로가 함께 쓰는 단일 계약.

설계 근거: ERD/A307_DAMAGE_ROI_VECTOR_DB_DESIGN.md 3·4·5·6절
    "과거 사례와 신규 입력은 반드시 동일한 임베딩 모델과 ROI 전처리를 사용한다"(8절)

기본값은 설계 4절 그대로다.

    ROI = 손상 영역 + 주변 부품 문맥
    - 초기 패딩 비율: bbox 가로·세로의 20%
    - 실험 범위: 15~30%
    - 이미지 경계를 넘어가는 좌표는 원본 크기로 제한
    - segmentation mask가 있으면 mask의 최소 bounding rectangle을 기준으로 사용
    - 손상 bbox가 너무 작거나 신뢰도가 낮으면 LOW_CONFIDENCE 처리

설계에 없는 규칙(min_side·max_aspect)은 전부 기본 비활성이다. 실험으로 켜고,
효과가 확인되면 설계 문서를 고친 뒤 기본값을 바꾼다. 순서를 뒤집지 않는다.

2026-09-09 표본 실측 (damage_part 라벨 1,200개 / 손상 4,716개):
  - bbox는 polygon 최소 bounding rect와 99.45% 일치 -> 4절 mask 조항은 bbox로 충족
  - 경계 clip으로 ROI가 줄어드는 경우 7.0%
  - min_side=64를 켜면 53.8%에서 패딩 20%가 덮어써지고, 34.6%가 실험 범위를 벗어난다
    -> 그래서 기본 비활성이다
  - PIL thumbnail()은 축소만 하므로 224 캔버스의 평균 89.5%가 회색 패딩이었다.
    letterbox()가 확대도 하도록 고친 것은 설계 이탈이 아니라 4절 준수 수정이다.

2026-09-11 same-image damage-part pairing baseline:
  - ``analyze_damage_part_pairing.py``의 seed ``a307-damage-part-pairing-v1``로
    TRAIN+VALIDATION에서 hash 선택한 label 1,200개, damage ROI 4,505개 기준
    PAIRED 3,192(70.9%), UNPAIRED 691(15.3%), AMBIGUOUS 622(13.8%)
  - 표본 선택은 파일명 정렬이 아니라 고정 hash 순서다. 따라서 같은 seed와
    dataset-root를 사용하면 동일 표본을 재현할 수 있다.
  - 0.5 threshold sweep (hash 표본):
    0.3 = 69.2/12.8/17.9%, 0.4 = 70.4/13.9/15.7%,
    0.5 = 70.9/15.3/13.8%, 0.6 = 71.7/16.9/11.5%,
    0.7 = 71.7/18.5/9.8% (PAIRED/UNPAIRED/AMBIGUOUS)
  - 0.5에서 bbox AMBIGUOUS 622건 중 polygon을 픽셀 mask로 rasterize해 다시
    계산했을 때 17건은 다중 part가 남고, 605건은 bbox 근사에서만 생긴 후보였다.
    이 결과는 분석용 참고값이며 실제 polygon 규칙 확정 전 수동 검토가 필요하다.
  - 현재 0.5 threshold는 최종 tuning 값이 아니라 초기 baseline이다.
  - 위 수치는 캐시 기반 표본 비교 결과이며, 전체 corpus 전수 sweep 결과로 해석하지 않는다.

2026-09-09 부품 잘림 실측 (TL_damage_part 라벨 무작위 1,200개):
  - 부품 annotation 1,755개 중 경계 접촉 54개 = 3.1% (TOL=1px)
    접촉 변은 위 22 / 왼 14 / 아래 11 / 오른 11로 한쪽에 쏠리지 않는다
  - 톨러런스 민감도: 0px 2.2% / 1px 3.1% / 2px 3.6% / 3px 4.2% / 5px 5.2% / 10px 7.9%
    자연스러운 절벽이 없다. 경계까지 최소거리 중앙값 62px, 하위 5% 5px.
    TOL은 손으로 정하는 값이며 1px을 기본으로 둔다 — 좌표에서 보이는 거리라
    표본을 눈으로 확인해 조정할 수 있다
  - 손상 annotation 4,503개 중 부품 연결 3,828(85.0%) / 미연결 675(15.0%)
    연결된 것 중 PARTIAL_PART 130개 = 3.4%, 전체 손상 기준 2.9%
  - 즉 PART_BOX_UNKNOWN(15.0%)이 PARTIAL_PART(2.9%)보다 5배 크다.
    부품 잘림보다 부품 연결 실패가 검색 품질에 더 큰 변수다
"""
from __future__ import annotations

from dataclasses import dataclass

from PIL import Image

# ── 설계 4절 기본값 ───────────────────────────────────────────────
PAD_RATIO = 0.20
PAD_RATIO_RANGE = (0.15, 0.30)
INPUT_SIZE = 224
LETTERBOX_FILL = (114, 114, 114)

# ── 설계에 없는 실험 파라미터. 기본 비활성 ────────────────────────
MIN_SIDE = 0          # >0이면 ROI 단변 최소 픽셀을 보장. 설계 4절에 없다
MAX_ASPECT = 0.0      # >0이면 ROI 종횡비 상한. 설계 4절에 없다

# ── 품질 판정 (설계 6절 quality_status 허용값) ────────────────────
QUALITY_GOOD = "GOOD"
QUALITY_LOW_CONFIDENCE = "LOW_CONFIDENCE"
QUALITY_INVALID = "INVALID"
QUALITY_PARTIAL_PART = "PARTIAL_PART"

LOW_CONFIDENCE_MIN_AREA = 1024   # 32x32 미만은 확대해도 보간 얼룩만 남는다
LOW_CONFIDENCE_MIN_SIDE = 16

# 부품-손상 연결 (설계 5절)
PART_LINK_MIN_OVERLAP = 0.5
# IoU가 아니라 damage 면적 대비 교집합 면적이다. part bbox가 damage보다 훨씬
# 크므로 IoU를 쓰면 "이 손상이 이 부품 안에 있는가"를 과소평가한다.
PART_LINK_RULE_VERSION = "bbox-damage-coverage-v1"   # 실제 mask 교차 기준은 v2 후보

MASK_RULE_VERSION = "bbox-as-min-rect-v1"

# 부품 잘림 판정 (설계 6절 feature_quality의 PARTIAL_PART)
PART_CLIP_TOLERANCE_PX = 1.0
PART_CLIP_RULE_VERSION = "part-bbox-touches-image-edge-v1"


def preprocessing_version(
    pad_ratio: float = PAD_RATIO,
    min_side: int = MIN_SIDE,
    max_aspect: float = MAX_ASPECT,
    size: int = INPUT_SIZE,
) -> str:
    """전처리 계약 지문. embedding_model_version.preprocessing_version에 넣는다."""
    parts = [f"pad{round(pad_ratio * 100)}", f"lb{size}gray"]
    if min_side:
        parts.insert(1, f"min{min_side}")
    if max_aspect:
        parts.insert(-1, f"ar{max_aspect:g}")
    return "-".join(parts)


PREPROCESSING_VERSION = preprocessing_version()


class RoiError(ValueError):
    """ROI를 만들 수 없는 입력. 조용히 넘기지 않고 격리한다."""


@dataclass(frozen=True)
class Roi:
    box: tuple[int, int, int, int]        # (x0, y0, x1, y1) 픽셀
    padding_ratio: float                  # 요청한 패딩 비율 (설계 roi_padding_ratio)
    effective_padding: tuple[float, float]  # 실제 적용된 (가로, 세로) 비율
    quality_status: str
    preprocessing_version: str
    clipped: bool                         # 경계에 걸려 원본 크기로 제한됐는지
    experiment_applied: bool              # 설계에 없는 규칙이 박스를 바꿨는지

    @property
    def roi_bbox(self) -> tuple[int, int, int, int]:
        """repair_case_roi_embedding.roi_bbox 저장용 [x, y, w, h]"""
        x0, y0, x1, y1 = self.box
        return (x0, y0, x1 - x0, y1 - y0)

    @property
    def is_design_conformant(self) -> bool:
        """설계 4절 규칙만으로 만들어진 ROI인가.

        경계 clip은 설계가 지시한 동작이므로 위반이 아니다. min_side·max_aspect
        같은 설계 밖 규칙이 박스를 바꿨을 때만 False다.
        """
        return not self.experiment_applied


def mask_min_rect(segmentation) -> tuple[float, float, float, float] | None:
    """설계 4절: segmentation mask의 최소 bounding rectangle.

    AI-Hub 라벨의 segmentation은 [[[[x, y], ...]]] 형태로 중첩돼 있다.
    좌표가 없으면 None을 돌려주고 호출부가 bbox로 되돌아간다.
    """
    pts: list[tuple[float, float]] = []

    def walk(node) -> None:
        if (isinstance(node, (list, tuple)) and len(node) == 2
                and all(isinstance(v, (int, float)) for v in node)):
            pts.append((float(node[0]), float(node[1])))
        elif isinstance(node, (list, tuple)):
            for child in node:
                walk(child)

    walk(segmentation or [])
    if not pts:
        return None
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return (min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys))


def source_bbox(annotation: dict) -> tuple[float, float, float, float]:
    """설계 4절 우선순위: mask 최소 bounding rect > bbox."""
    rect = mask_min_rect(annotation.get("segmentation"))
    if rect and rect[2] > 0 and rect[3] > 0:
        return rect
    bbox = annotation.get("bbox")
    if not bbox or len(bbox) != 4:
        raise RoiError(f"bbox도 segmentation도 쓸 수 없다: {annotation.get('id')}")
    return tuple(float(v) for v in bbox)  # type: ignore[return-value]


def roi_quality(bbox: tuple[float, float, float, float],
                confidence: float | None = None,
                min_confidence: float = 0.0) -> str:
    """설계 4절: 너무 작거나 신뢰도가 낮으면 LOW_CONFIDENCE.

    색인에서 빼는 판단은 여기서 하지 않는다. 표시만 하고 정책은 호출부가 정한다.
    """
    _, _, w, h = bbox
    if w <= 0 or h <= 0:
        return QUALITY_INVALID
    if min(w, h) < LOW_CONFIDENCE_MIN_SIDE or w * h < LOW_CONFIDENCE_MIN_AREA:
        return QUALITY_LOW_CONFIDENCE
    if confidence is not None and min_confidence and confidence < min_confidence:
        return QUALITY_LOW_CONFIDENCE
    return QUALITY_GOOD


def part_clipped(part_bbox: tuple[float, float, float, float],
                 image_size: tuple[int, int],
                 tolerance_px: float = PART_CLIP_TOLERANCE_PX) -> bool:
    """부품 bbox가 이미지 경계에 닿았는지. 닿았으면 부품 일부가 화면 밖이다.

    좌표 비교만 한다. 부품 전체 크기를 추정하지 않는다 — 화면 밖 면적은 사진에
    없으므로, 추정하는 순간 판정도 추정이 된다. 그래서 임계값을 정당화할 필요가
    없고 표본을 눈으로 확인해 정오를 셀 수 있다.

    못 잡는 것 — 다른 차·기둥에 가려진(occluded) 부품. 경계에 닿지 않으므로 참이
    되지 않는다. MVP 범위 밖이다.

    roi_box가 돌려주는 clip 여부와 다른 값이다. 그쪽은 손상 bbox에 패딩을 준
    ROI가 잘렸는지고, 이쪽은 부품 자체가 잘렸는지다. 손상이 화면 한가운데 있어도
    부품은 잘릴 수 있다.
    """
    W, H = image_size
    if W <= 0 or H <= 0:
        raise RoiError(f"이미지 크기가 유효하지 않다: {image_size}")
    x, y, w, h = part_bbox
    if w <= 0 or h <= 0:
        raise RoiError(f"부품 bbox 폭·높이가 0 이하다: {part_bbox}")
    return (x <= tolerance_px or y <= tolerance_px
            or x + w >= W - tolerance_px or y + h >= H - tolerance_px)


def feature_quality(roi_status: str, part_is_clipped: bool | None) -> str:
    """설계 6절 feature_quality 4종. roi_quality 위에 부품 잘림을 얹는다.

    INVALID·LOW_CONFIDENCE가 우선이다. 손상 자체를 믿을 수 없는 상태에서 부품이
    잘렸는지 따지는 것은 의미가 없다.

    part_is_clipped가 None이면 부품 영역을 받지 못한 것이다. 판정하지 않고
    roi_status를 그대로 둔다 — 모르는 것을 GOOD으로도 PARTIAL_PART로도
    바꾸지 않는다. 호출부가 quality_reasons에 그 사실을 남긴다.
    """
    if roi_status != QUALITY_GOOD:
        return roi_status
    if part_is_clipped:
        return QUALITY_PARTIAL_PART
    return QUALITY_GOOD


def roi_box(
    bbox: tuple[float, float, float, float],
    image_size: tuple[int, int],
    pad_ratio: float = PAD_RATIO,
    min_side: int = MIN_SIDE,
    max_aspect: float = MAX_ASPECT,
) -> tuple[tuple[int, int, int, int], tuple[float, float], bool, bool]:
    """손상 bbox -> ((x0,y0,x1,y1), 유효패딩(가로,세로), clip여부, 실험규칙적용여부).

    설계 4절 기본 경로는 '패딩 -> 경계를 원본 크기로 제한'뿐이다.
    min_side·max_aspect는 0이면 개입하지 않는다.
    """
    W, H = image_size
    if W <= 0 or H <= 0:
        raise RoiError(f"이미지 크기가 유효하지 않다: {image_size}")
    x, y, w, h = bbox
    if w <= 0 or h <= 0:
        raise RoiError(f"bbox 폭·높이가 0 이하다: {bbox}")

    x0, y0 = x - w * pad_ratio, y - h * pad_ratio
    x1, y1 = x + w + w * pad_ratio, y + h + h * pad_ratio

    experiment_applied = False
    if min_side or max_aspect:
        cx, cy = x + w / 2.0, y + h / 2.0
        rw, rh = x1 - x0, y1 - y0
        before = (rw, rh)
        if min_side:
            rw, rh = max(rw, float(min_side)), max(rh, float(min_side))
        if max_aspect:
            if rw / rh > max_aspect:
                rh = rw / max_aspect
            elif rh / rw > max_aspect:
                rw = rh / max_aspect
        experiment_applied = (rw, rh) != before
        x0, y0 = cx - rw / 2.0, cy - rh / 2.0
        x1, y1 = cx + rw / 2.0, cy + rh / 2.0

    # 설계 4절: "이미지 경계를 넘어가는 좌표는 원본 크기로 제한"
    clipped = x0 < 0 or y0 < 0 or x1 > W or y1 > H
    x0, y0 = max(0.0, x0), max(0.0, y0)
    x1, y1 = min(float(W), x1), min(float(H), y1)
    if x1 - x0 < 1 or y1 - y0 < 1:
        raise RoiError(f"경계 제한 후 ROI가 비었다: {bbox} in {image_size}")

    eff = (((x1 - x0) / w - 1) / 2, ((y1 - y0) / h - 1) / 2)
    box = (int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1)))
    return box, eff, clipped, experiment_applied


def letterbox(
    im: Image.Image,
    size: int = INPUT_SIZE,
    fill: tuple[int, int, int] = LETTERBOX_FILL,
) -> Image.Image:
    """설계 4절 '종횡비 유지 resize + letterbox'. 축소와 확대를 모두 한다.

    PIL thumbnail()을 쓰면 안 된다 — 원본보다 큰 목표 크기에서 아무 일도
    하지 않아 작은 ROI가 회색 캔버스 속 점으로 남는다.
    """
    if im.width <= 0 or im.height <= 0:
        raise RoiError(f"crop 결과가 비어 있다: {im.size}")
    scale = size / max(im.width, im.height)
    nw = max(1, int(round(im.width * scale)))
    nh = max(1, int(round(im.height * scale)))
    canvas = Image.new("RGB", (size, size), fill)
    canvas.paste(im.resize((nw, nh), Image.BICUBIC),
                 ((size - nw) // 2, (size - nh) // 2))
    return canvas


def make_roi(
    image: Image.Image,
    annotation: dict,
    pad_ratio: float = PAD_RATIO,
    min_side: int = MIN_SIDE,
    max_aspect: float = MAX_ASPECT,
    confidence: float | None = None,
) -> tuple[Image.Image, Roi]:
    """손상 주석 -> (모델 입력 이미지, 적재용 ROI 메타).

    annotation은 AI-Hub 라벨의 한 행 또는 정규화된 손상 인스턴스.
    bbox 대신 segmentation이 있으면 최소 bounding rect를 쓴다(설계 4절).
    """
    bbox = source_bbox(annotation)
    quality = roi_quality(bbox, confidence=confidence)
    if quality == QUALITY_INVALID:
        raise RoiError(f"bbox가 퇴화했다: {bbox}")
    box, eff, clipped, exp = roi_box(bbox, image.size, pad_ratio=pad_ratio,
                                     min_side=min_side, max_aspect=max_aspect)
    crop = image.convert("RGB").crop(box)
    meta = Roi(box=box, padding_ratio=pad_ratio, effective_padding=eff,
               quality_status=quality, clipped=clipped, experiment_applied=exp,
               preprocessing_version=preprocessing_version(
                   pad_ratio, min_side, max_aspect))
    return letterbox(crop), meta


def overlap(damage_bbox, part_bbox) -> float:
    """damage coverage = intersection(part, damage) / area(damage).

    IoU가 아닌 비대칭 coverage다. ``source_bbox``가 segmentation의 최소
    bounding rectangle을 우선 사용하므로, 얇은 대각선·L자형 polygon은 실제
    pixel coverage보다 크게 계산될 수 있다.
    """
    dx, dy, dw, dh = damage_bbox
    px, py, pw, ph = part_bbox
    if dw <= 0 or dh <= 0:
        return 0.0
    ix = max(0.0, min(dx + dw, px + pw) - max(dx, px))
    iy = max(0.0, min(dy + dh, py + ph) - max(dy, py))
    return (ix * iy) / (dw * dh)


def link_parts(
    damage_annotation: dict,
    part_annotations: list[dict],
    min_overlap: float = PART_LINK_MIN_OVERLAP,
) -> list[tuple[dict, float]]:
    """coverage가 임계값 이상인 부품을 겹침 큰 순으로 모두 돌려준다.

    설계는 "손상이 부품 경계에 걸쳐 있으면 일정 임계값 이상인 복수 부품과
    연결할 수 있다"고 했으므로 하나로 줄이지 않는다.

    빈 리스트는 오류가 아니다. damage_part 그룹에서도 부품 영역 밖 손상이
    존재한다. 이 경우 부품을 억지로 보완하지 않고 매칭 없음으로 남긴다. 견적
    repair 필드나 다른 이미지의 part를 ROI 부품으로 전파하지 않는다.

    현재 0.5는 초기 보수 기준이다. 서로 다른 part_code 후보가 2개 이상이면
    점수 차이가 커도 primary를 임의 확정하지 않고 ambiguous로 남긴다. 같은
    part_code의 annotation이 여러 개 겹치는 경우에는 표준 부품 의미가 같으므로
    호출부가 하나의 strict part로 합칠 수 있다.

    설계 5절 공식 보완: intersection/area(damage)는 부품 영역이 서로 포개져
    있을 때 둘 다 1.0이 되어 기본 부품을 못 고른다(범퍼가 펜더를 포함하는
    실제 사례가 있다). 동률이면 면적이 작은 부품, 즉 더 구체적인 부품을
    앞에 둔다. 이 보완 규칙은 PART_LINK_RULE_VERSION에 기록한다.
    """
    d = source_bbox(damage_annotation)
    scored = []
    for ann in part_annotations:
        try:
            p = source_bbox(ann)
        except RoiError:
            continue
        c = overlap(d, p)
        if c >= min_overlap:
            scored.append((ann, c, p[2] * p[3]))
    scored.sort(key=lambda t: (-t[1], t[2]))
    return [(ann, c) for ann, c, _ in scored]


def primary_part(damage_annotation: dict, part_annotations: list[dict],
                 min_overlap: float = PART_LINK_MIN_OVERLAP) -> dict | None:
    """설계 5절 '가장 많이 겹치는 부품을 기본 부품으로 선택'."""
    linked = link_parts(damage_annotation, part_annotations, min_overlap)
    return linked[0][0] if linked else None


def is_closeup(bbox: tuple[float, float, float, float],
               image_size: tuple[int, int],
               area_threshold: float = 0.5) -> bool:
    """설계 3절: damage 이미지에서 '손상이 이미지 대부분을 차지'하는지.

    참이면 패딩 crop 대신 원본 전체를 ROI로 쓴다(source_view=CLOSEUP).
    """
    W, H = image_size
    _, _, w, h = bbox
    return (w * h) / float(W * H) >= area_threshold if W and H else False


def make_roi_for_group(
    image: Image.Image,
    annotation: dict,
    source_dataset: str,
    pad_ratio: float = PAD_RATIO,
    **kwargs,
) -> tuple[Image.Image, Roi, str]:
    """설계 3절 이미지 종류별 처리. source_view를 함께 돌려준다."""
    bbox = source_bbox(annotation)
    if source_dataset == "damage" and is_closeup(bbox, image.size):
        quality = roi_quality(bbox)
        W, H = image.size
        meta = Roi(box=(0, 0, W, H), padding_ratio=pad_ratio,
                   effective_padding=((W / bbox[2] - 1) / 2, (H / bbox[3] - 1) / 2),
                   quality_status=quality, clipped=False, experiment_applied=False,
                   preprocessing_version=preprocessing_version(pad_ratio, **{
                       k: v for k, v in kwargs.items() if k in ("min_side", "max_aspect")}))
        return letterbox(image.convert("RGB")), meta, "CLOSEUP"
    roi_img, meta = make_roi(image, annotation, pad_ratio=pad_ratio, **kwargs)
    view = "FULL_VEHICLE" if source_dataset == "damage_part" else "PART_VIEW"
    return roi_img, meta, view
