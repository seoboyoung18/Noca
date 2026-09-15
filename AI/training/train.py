"""YOLO26 세그멘테이션 미니학습."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["damage", "damage_part"], required=True)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--imgsz", type=int, default=960)
    ap.add_argument("--batch", type=int, default=-1)
    ap.add_argument("--weights", default=str(ROOT / "models/base/yolo26n-seg.pt"))
    args = ap.parse_args()

    print("TensorBoard: venv/Scripts/tensorboard.exe --logdir runs")

    from ultralytics import YOLO

    try:
        model = YOLO(args.weights)
    except Exception as e:
        print(f"[에러] 가중치 '{args.weights}' 로드 실패: {e}")
        print("--weights로 유효한 YOLO segmentation 초기 가중치를 지정하세요. 자동 대체하지 않고 종료합니다.")
        return

    data = ROOT / "datasets" / args.model / f"{args.model}.yaml"
    model.train(
        data=str(data),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=6,
        val=False,
        project=str(ROOT / "artifacts" / "training" / args.model),
        name="train",
        plots=True,
        exist_ok=True,
    )
    model.val(data=str(data), imgsz=args.imgsz, device=6)


if __name__ == "__main__":
    main()
