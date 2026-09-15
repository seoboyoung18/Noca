"""로컬 시각 확인용 추론. AI 서버와 독립적으로 결과 이미지를 저장한다."""
import argparse
from datetime import datetime
from pathlib import Path

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", default=str(ROOT / "models/damage/damage_best-60ep.pt"))
    parser.add_argument("--source", default=str(ROOT / "samples/input"))
    parser.add_argument("--out", default=str(ROOT / "artifacts/inference"))
    parser.add_argument("--imgsz", type=int, default=960)
    args = parser.parse_args()

    model = YOLO(args.weights)
    model.predict(
        source=args.source,
        imgsz=args.imgsz,
        save=True,
        project=args.out,
        name=datetime.now().strftime("%Y%m%d_%H%M%S"),
    )


if __name__ == "__main__":
    main()
