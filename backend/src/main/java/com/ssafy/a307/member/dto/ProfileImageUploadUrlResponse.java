package com.ssafy.a307.member.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 발급된 업로드 URL.
 *
 * @param uploadKey       업로드가 끝나면 {@code PUT /api/members/me/profile-image} 에
 *                        그대로 돌려줘야 하는 값
 * @param url             브라우저가 PUT 할 주소
 * @param requiredHeaders PUT 에 반드시 실어야 하는 헤더. 서명에 포함돼 있어
 *                        빠지거나 다르면 S3 가 403 으로 거절한다
 */
public record ProfileImageUploadUrlResponse(
        String uploadKey,
        String url,
        Instant expiresAt,
        Map<String, String> requiredHeaders) {
}
