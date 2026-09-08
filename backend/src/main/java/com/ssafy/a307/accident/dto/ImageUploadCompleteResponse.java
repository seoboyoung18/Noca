package com.ssafy.a307.accident.dto;

import java.util.List;

/**
 * 완료 통보 응답. <b>전체 롤백하지 않는다</b> — 일부가 실패해도 나머지는 저장되고,
 * 실패분만 다시 업로드·통보할 수 있다(요구사항 22행).
 * <p>
 * 그래서 개별 이미지의 검증 실패는 HTTP 400 이 아니라 {@code results[].status = FAILED} 로
 * 나온다. 400·404 는 <b>요청 자체</b>가 잘못된 경우에만 쓴다 — 빈 목록, 소유하지 않은 imageId,
 * 같은 imageId 중복.
 */
public record ImageUploadCompleteResponse(
        int requested,
        int succeeded,
        int failed,
        List<AccidentImageResultResponse> results
) {

    public static ImageUploadCompleteResponse of(List<AccidentImageResultResponse> results) {
        int failed = (int) results.stream()
                .filter(r -> r.status() == ImageProcessingStatus.FAILED)
                .count();
        return new ImageUploadCompleteResponse(
                results.size(), results.size() - failed, failed, results);
    }
}
