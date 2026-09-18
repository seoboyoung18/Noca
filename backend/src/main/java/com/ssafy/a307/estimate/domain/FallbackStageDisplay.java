package com.ssafy.a307.estimate.domain;

/**
 * 완화 단계를 사람이 읽는 말로 (S15P21A307-547).
 *
 * <p><b>{@code RepairMethodDisplay} 와 같은 자리다.</b> 이 저장소는 한글 라벨을 서버가 만들지
 * 않는 것이 원칙이지만({@code AnalysisProgressResponse}), 두 가지는 예외다 — <b>PDF 는 서버가
 * 그리는 문서</b>라 그 안의 문구를 화면이 정할 수 없고, 견적 응답은 이미
 * {@code repairMethodDisplayName} 을 같은 이유로 담고 있다.
 *
 * <p>문구를 짧게 둔 이유가 있다. 이 값은 표의 한 칸에 들어가고, 옆 칸에 사례 건수가 함께
 * 있으므로 "동일 차량명 3건" 처럼 읽힌다. 설명은 근거 문장이 맡는다.
 */
public final class FallbackStageDisplay {

    private FallbackStageDisplay() {
    }

    /**
     * @return 표시 문구. <b>모르는 값이면 {@code null}</b> — 화면이 그 칸을 비운다.
     *         새 단계가 생겼는데 여기를 안 고친 것을 지어낸 이름으로 덮지 않는다
     */
    public static String displayNameOf(FallbackStage stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case MODEL -> "동일 차량명";
            case PRICE_TIER -> "비슷한 수리비대";
            // AI 는 2026-09-17(S15P21A307-235) 이후 이 단계를 내지 않지만 enum 에 남아 있다.
            // 예전 견적의 스냅샷에는 들어 있을 수 있어 표기를 지우지 않는다.
            case CAR_CLASS -> "같은 차급";
            case ALL -> "전체 사례";
        };
    }
}
