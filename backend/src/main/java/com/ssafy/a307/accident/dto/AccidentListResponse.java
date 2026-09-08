package com.ssafy.a307.accident.dto;

import java.util.List;

/** 공통 규약이 {@code data} 에 객체를 요구하므로 배열을 그대로 넣지 않고 감싼다. */
public record AccidentListResponse(List<AccidentResponse> accidents) {
}
