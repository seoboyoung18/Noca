package com.ssafy.a307.accident.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 파일 한 건에 발급된 업로드 정보.
 *
 * @param uploadUrl       브라우저가 이 URL 로 직접 PUT 한다. 바이트는 서버를 거치지 않는다
 * @param uploadMethod    항상 {@code PUT}. 값으로 내려주는 이유는 POST form 방식으로 바꿀 여지를 남기기 위해서다
 * @param requiredHeaders PUT 시 그대로 보내야 하는 헤더. 빠지면 서명이 맞지 않아 저장소가 403 을 준다
 * @param expiresAt       이 시각 이후에는 재발급이 필요하다
 * @param angleCode       요청에 담겼던 값을 되돌려준다. <b>서버에 저장되지 않는다</b>(answer25 D1)
 */
public record IssuedUploadUrl(
        Long imageId,
        String originalFilename,
        String angleCode,
        String s3Key,
        String uploadUrl,
        String uploadMethod,
        Map<String, String> requiredHeaders,
        Instant expiresAt
) {
}
