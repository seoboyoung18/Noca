from __future__ import annotations

from pathlib import Path
from urllib.parse import urlparse

import httpx


class ImageFetchError(RuntimeError):
    pass


async def download_image(url: str, target_dir: Path, image_id: int) -> Path:
    suffix = Path(urlparse(url).path).suffix.lower()
    if suffix not in {".jpg", ".jpeg", ".png", ".webp"}:
        suffix = ".jpg"
    path = target_dir / f"{image_id}{suffix}"
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(20.0), follow_redirects=False) as client:
            response = await client.get(url)
            response.raise_for_status()
    except httpx.HTTPError as exc:
        raise ImageFetchError(f"failed to download image {image_id}") from exc
    if not response.content:
        raise ImageFetchError(f"downloaded image {image_id} is empty")
    path.write_bytes(response.content)
    return path

