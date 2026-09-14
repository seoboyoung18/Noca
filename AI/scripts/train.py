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
    ap.add_argument("--weights", default="yolo26s-seg.pt")
    args = ap.parse_args()

    print("TensorBoard: venv/Scripts/tensorboard.exe --logdir runs")

    from ultralytics import YOLO

    try:
        model = YOLO(args.weights)
    except Exception as e:
        print(f"[에러] 가중치 '{args.weights}' 로드 실패: {e}")
        print("yolo26 seg 가중치가 없으면 'yolo11n-seg.pt'로 재시도할 수 있습니다 "
              "(--weights yolo11n-seg.pt). 자동 대체하지 않고 종료합니다.")
        return

    data = ROOT / "datasets" / args.model / f"{args.model}.yaml"
    model.train(
        data=str(data),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=6,
        val=False,
        project=f"runs/{args.model}",
        name="train",
        plots=True,
        exist_ok=True,
    )
    model.val(data=str(data), imgsz=args.imgsz, device=6)


if __name__ == "__main__":
    main()
