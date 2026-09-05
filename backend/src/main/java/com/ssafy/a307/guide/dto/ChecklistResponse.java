package com.ssafy.a307.guide.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * {@code GET /api/guides/checklist} 응답. 배열을 그대로 내보내지 않고 객체로 감싼다
 * (공통 규약 — {@code prompt1.md} 4장).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChecklistResponse(List<ChecklistStep> steps) {
}
