"""차량 파손 이미지 데이터(zip) -> YOLO 세그멘테이션 데이터셋 변환.

zip은 해제하지 않고 zipfile로 필요한 멤버만 스트리밍 추출한다.
"""
import argparse
import io
import json
import random
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "160. 차량파손 이미지 데이터" / "01.데이터"

CFG = {
    "damage": {
        "field": "damage",
        "train_img": "1.Training/1.원천데이터/TS_damage.zip",
        "train_lbl": "1.Training/2.라벨링데이터/TL_damage.zip",
        "val_img": "2.Validation/1.원천데이터/VS_damage.zip",
        "val_lbl": "2.Validation/2.라벨링데이터/VL_damage.zip",
    },
    "damage_part": {
        "field": "part",
        "train_img": "1.Training/1.원천데이터/TS_damage_part.zip",
        "train_lbl": "1.Training/2.라벨링데이터/TL_damage_part.zip",
        "val_img": "2.Validation/1.원천데이터/VS_damage_part.zip",
        "val_lbl": "2.Validation/2.라벨링데이터/VL_damage_part.zip",
    },
}


def iter_polygons(seg):
    """segmentation( [[[[x,y],...]]] )에서 폴리곤([[x,y],...])들을 하나씩 반환."""
    if not isinstance(seg, list):
        return
    for group in seg:
        if not isinstance(group, list):
            continue
        for poly in group:
            if isinstance(poly, list) and poly and isinstance(poly[0], list):
                yield poly


def image_size(img_bytes, json_img):
    w = json_img.get("width")
    h = json_img.get("height")
    if w and h:
        return float(w), float(h)
    from PIL import Image
    with Image.open(io.BytesIO(img_bytes)) as im:
        return float(im.width), float(im.height)


def parse_split(lbl_zip, img_zip, field, selected):
    """선택된 json을 파싱해 레코드 목록과 스킵 수 반환.

    레코드: (stem, img_bytes, [(raw_cls, [정규화 xy...]), ...])
    """
    lz = zipfile.ZipFile(BASE / lbl_zip)
    iz = zipfile.ZipFile(BASE / img_zip)
    img_by_stem = {Path(n).stem: n for n in iz.namelist() if n.lower().endswith(".jpg")}

    records = []
    skipped = 0
    for member in selected:
        stem = Path(member).stem
        img_member = img_by_stem.get(stem)
        if img_member is None:
            skipped += 1
            continue
        data = json.loads(lz.read(member))
        anns = data.get("annotations", [])
        if isinstance(anns, dict):
            anns = [anns]
        img_bytes = iz.read(img_member)
        w, h = image_size(img_bytes, data.get("images", {}))

        polys = []
        for a in anns:
            raw = a.get(field)
            if raw is None:
                continue
            for poly in iter_polygons(a.get("segmentation")):
                pts = [p for p in poly if isinstance(p, list) and len(p) == 2]
                if len(pts) < 3:
                    continue
                norm = []
                for x, y in pts:
                    norm.append(min(max(float(x) / w, 0.0), 1.0))
                    norm.append(min(max(float(y) / h, 0.0), 1.0))
                polys.append((raw, norm))
        if not polys:
            skipped += 1
            continue
        records.append((stem, img_bytes, polys))

    lz.close()
    iz.close()
    return records, skipped


def write_split(records, name_to_idx, img_dir, lbl_dir, class_counts):
    img_dir.mkdir(parents=True, exist_ok=True)
    lbl_dir.mkdir(parents=True, exist_ok=True)
    for stem, img_bytes, polys in records:
        (img_dir / f"{stem}.jpg").write_bytes(img_bytes)
        lines = []
        for raw, norm in polys:
            class_counts[raw] = class_counts.get(raw, 0) + 1
            lines.append(str(name_to_idx[raw]) + " " + " ".join(f"{v:.6f}" for v in norm))
        (lbl_dir / f"{stem}.txt").write_text("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["damage", "damage_part"], required=True)
    ap.add_argument("--train-count", type=int, default=3000)
    ap.add_argument("--val-count", type=int, default=300)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="datasets")
    args = ap.parse_args()

    cfg = CFG[args.model]
    field = cfg["field"]

    with zipfile.ZipFile(BASE / cfg["train_lbl"]) as z:
        train_jsons = [n for n in z.namelist() if n.lower().endswith(".json")]
    with zipfile.ZipFile(BASE / cfg["val_lbl"]) as z:
        val_jsons = [n for n in z.namelist() if n.lower().endswith(".json")]

    rnd = random.Random(args.seed)
    train_sel = rnd.sample(train_jsons, min(args.train_count, len(train_jsons)))
    val_sel = rnd.sample(val_jsons, min(args.val_count, len(val_jsons)))
    print(f"선택: train {len(train_sel)}, val {len(val_sel)} (총 {len(train_sel) + len(val_sel)})")

    train_recs, train_skip = parse_split(cfg["train_lbl"], cfg["train_img"], field, train_sel)
    val_recs, val_skip = parse_split(cfg["val_lbl"], cfg["val_img"], field, val_sel)

    names = sorted({raw for recs in (train_recs, val_recs) for _, _, polys in recs for raw, _ in polys})
    name_to_idx = {n: i for i, n in enumerate(names)}

    out_root = Path(args.out) / args.model
    class_counts = {}
    write_split(train_recs, name_to_idx, out_root / "images/train", out_root / "labels/train", class_counts)
    write_split(val_recs, name_to_idx, out_root / "images/val", out_root / "labels/val", class_counts)

    yaml_path = out_root / f"{args.model}.yaml"
    lines = [
        f"path: {out_root.resolve().as_posix()}",
        "train: images/train",
        "val: images/val",
        "names:",
    ]
    lines += [f"  {i}: {n}" for i, n in enumerate(names)]
    yaml_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"처리 완료: train {len(train_recs)}, val {len(val_recs)}")
    print(f"스킵: train {train_skip}, val {val_skip}")
    print(f"최종 이미지 수: {len(train_recs) + len(val_recs)}, 라벨 txt 수: {len(train_recs) + len(val_recs)}")
    print(f"클래스 {len(names)}개: {names}")
    print("클래스별 인스턴스 카운트:")
    for n in names:
        print(f"  {name_to_idx[n]} {n}: {class_counts.get(n, 0)}")
    print(f"data.yaml: {yaml_path.resolve().as_posix()}")


if __name__ == "__main__":
    main()
