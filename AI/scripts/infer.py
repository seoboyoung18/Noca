"""로컬 추론: best.pt로 이미지 폴더 예측 후 결과 저장."""
from datetime import datetime
from pathlib import Path

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent

model = YOLO(str(ROOT / "best(30ep).pt"))
model.predict(
    source=str(ROOT / "추론이미지"),
    imgsz=960,
    save=True,
    project=str(ROOT / "추론결과"),
    name=datetime.now().strftime("%Y%m%d_%H%M%S"),
)
