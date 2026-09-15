"""Request/response DTOs at the AI server HTTP boundary."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class Vehicle(BaseModel):
    model_id: int | None = Field(default=None, alias="modelId")
    manufacturer: str | None = None
    model_name: str | None = Field(default=None, alias="modelName")
    car_class: str | None = Field(default=None, alias="carClass")
    model_year: int | None = Field(default=None, alias="modelYear")

    model_config = {"populate_by_name": True}


class InputImage(BaseModel):
    image_id: int = Field(alias="imageId")
    url: str
    angle_code: str | None = Field(default=None, alias="angleCode")
    expires_at: str | None = Field(default=None, alias="expiresAt")

    model_config = {"populate_by_name": True}


class InferenceRequest(BaseModel):
    images: list[InputImage] = Field(min_length=1)


class SearchRequest(BaseModel):
    vehicle: Vehicle
    images: list[InputImage] = Field(min_length=1)
    image_results: list[dict[str, Any]] = Field(alias="imageResults", min_length=1)

    model_config = {"populate_by_name": True}


class EstimateRequest(BaseModel):
    vehicle: Vehicle
    parts: list[dict[str, Any]]


class AnalyzeRequest(InferenceRequest):
    job_id: int = Field(alias="jobId")
    request_id: str = Field(alias="requestId", min_length=1)
    vehicle: Vehicle
    callback_url: str = Field(alias="callbackUrl")

    model_config = {"populate_by_name": True}

