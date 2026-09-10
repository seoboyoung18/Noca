package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.VehicleInputType;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;

import java.time.Instant;

/**
 * 사고 이력 <b>목록</b>의 한 건(Task 225). 상세({@link AccidentResponse})와 나눈 이유는
 * 목록에만 필요한 값 — 썸네일·상태·예상 비용 — 을 상세 응답에 얹으면
 * {@code POST /api/accidents} 응답까지 함께 커지기 때문이다. 접수 직후에는 셋 다 비어 있다.
 *
 * <p>차량 필드 이름은 {@link AccidentResponse}·{@code VehicleResponse} 와 같다. FE 가 같은
 * 타입을 재사용할 수 있어야 한다.
 *
 * <p><b>썸네일 URL 은 저장하지 않는다.</b> 응답을 만들 때마다 서명하고 {@code thumbnailExpiresAt}
 * 이 지나면 다시 받아야 한다. 발급에 실패하면 그 건만 {@code null} 이고 나머지 필드는 그대로다 —
 * 목록 전체를 500 으로 뒤집지 않는다.
 *
 * @param status              유도값이다. {@link AccidentHistoryStatus} 참조
 * @param imageCount          등록된 이미지 장수. 완료 통보를 못 받은 것도 센다 — 화면의 "N장" 은
 *                            사용자가 올린 수이지 서버 처리 상태가 아니다
 * @param thumbnailUrl        가장 먼저 올린 이미지의 썸네일 조회 URL. 이미지가 없거나 아직
 *                            전처리 전이거나 서명에 실패하면 {@code null}
 * @param estimatedCostMedian 예상 수리비 중앙값. <b>견적이 없으면 {@code null} 이고, 지금은 항상
 *                            그렇다</b> — {@code estimate} 를 만드는 운영 코드가 아직 없다
 *                            ({@code S15P21A307-50} 비용 산정). 계약을 먼저 열어 두어
 *                            그쪽이 끝나면 FE 수정 없이 값이 채워진다
 */
public record AccidentSummaryResponse(
        Long accidentId,
        Long vehicleId,
        VehicleInputType vehicleInputType,
        Long modelId,
        String manufacturer,
        String modelName,
        VehicleType vehicleType,
        CarClass carClass,
        Integer modelYear,
        Instant createdAt,

        AccidentHistoryStatus status,
        int imageCount,
        String thumbnailUrl,
        Instant thumbnailExpiresAt,
        Integer estimatedCostMin,
        Integer estimatedCostMedian,
        Integer estimatedCostMax
) {

    /**
     * 생성자 투영이 쓰는 형태. 목록 전용 값은 뒤에 {@link #withDetails} 로 채운다.
     * <p>
     * 투영에서 한 번에 만들지 않는 이유는 썸네일·견적이 <b>다른 테이블의 다른 행 수</b>라
     * 조인하면 사고 한 건이 여러 행으로 불어나 페이징이 깨지기 때문이다. 페이지를 먼저 자르고
     * 그 id 들로만 모아 온다.
     */
    public AccidentSummaryResponse(
            Long accidentId,
            Long vehicleId,
            VehicleInputType vehicleInputType,
            Long modelId,
            String manufacturer,
            String modelName,
            VehicleType vehicleType,
            CarClass carClass,
            Integer modelYear,
            Instant createdAt) {
        this(accidentId, vehicleId, vehicleInputType, modelId, manufacturer, modelName,
                vehicleType, carClass, modelYear, createdAt,
                AccidentHistoryStatus.RECEIVED, 0, null, null, null, null, null);
    }

    public AccidentSummaryResponse withDetails(
            AccidentHistoryStatus status,
            int imageCount,
            String thumbnailUrl,
            Instant thumbnailExpiresAt,
            Integer estimatedCostMin,
            Integer estimatedCostMedian,
            Integer estimatedCostMax) {
        return new AccidentSummaryResponse(
                accidentId, vehicleId, vehicleInputType, modelId, manufacturer, modelName,
                vehicleType, carClass, modelYear, createdAt,
                status, imageCount, thumbnailUrl, thumbnailExpiresAt,
                estimatedCostMin, estimatedCostMedian, estimatedCostMax);
    }
}
