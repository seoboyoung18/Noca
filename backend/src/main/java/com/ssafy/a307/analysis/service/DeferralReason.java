package com.ssafy.a307.analysis.service;

/**
 * 검출을 받았지만 {@code damaged_part} 행을 만들지 <b>않은</b> 이유.
 *
 * <p>적재가 조용히 건너뛰면 "왜 화면에 이 부품이 없나" 를 나중에 아무도 답할 수 없다.
 * 그래서 건너뛴 건마다 이유를 남기고 {@link AnalysisIngestResult} 로 함께 돌려준다.
 *
 * <p><b>이것들은 오류가 아니다.</b> 계약은 지켰는데 서비스 스키마가 요구하는 값을
 * <b>근거 있게</b> 정할 수 없어 미룬 것이다. 근거가 생기면 그때 적재하면 된다.
 */
public enum DeferralReason {

    /**
     * 수리 방식 후보가 둘 이상이라 하나를 고를 근거가 없다.
     *
     * <p>계약의 {@code work_decision} 은 {@code "CANDIDATE"} 고정이고, {@code SEPARATED}·
     * {@code BREAKAGE} 는 {@code repair}/{@code exchange}, {@code CRUSHED} 는
     * {@code sheet_metal}/{@code exchange} 로 후보가 둘이다. 교환과 판금·수리를 가르는 축은
     * 심각도인데 그 규칙이 {@code S15P21A307-197} 로 롤백됐다.
     * <b>"항상 첫 후보" 같은 임의 규칙을 넣으면 근거 없는 값이 견적까지 흘러간다.</b>
     */
    AMBIGUOUS_WORK_CANDIDATE,

    /**
     * {@code confidence.part} 와 {@code confidence.damage} 가 둘 다 비었다.
     * {@code damaged_part.confidence} 는 NOT NULL 이라 넣을 값이 없다.
     */
    MISSING_CONFIDENCE,

    /** {@code part_code} 마스터에 없는 코드. FK 가 {@code ON DELETE RESTRICT} 라 INSERT 자체가 거절된다. */
    UNKNOWN_PART_CODE,

    /**
     * 비활성 부품 코드. {@code S15P21A307-363} 이 "비활성 코드는 신규 입력에서 제외" 로 정했고,
     * 적재도 신규 입력이라 같은 규칙을 따른다.
     */
    INACTIVE_PART_CODE,

    /**
     * 같은 작업에서 같은 부품이 <b>서로 다른</b> 손상·수리 방식으로 두 번 이상 나왔다.
     *
     * <p>{@code uk_dp (job_id, part_code)} 라 한 행만 남길 수 있는데 어느 쪽이 옳은지 정할 근거가 없다.
     * 여러 이미지에 걸친 같은 부품을 하나로 합치는 규칙은 <b>{@code S15P21A307-199}(김경연 담당)</b>
     * 의 범위다. 먼저 온 것을 이기게 두면 <b>문서를 넣은 순서가 결과를 바꾸므로</b>,
     * 이 저장소가 부품명 사전에서 쓴 것과 같은 방식으로 <b>겹친 쪽을 모두 뺀다.</b>
     */
    CONFLICTING_DUPLICATE_PART
}
