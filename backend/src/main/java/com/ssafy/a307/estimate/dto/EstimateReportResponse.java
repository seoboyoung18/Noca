package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemResponse;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemSource;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;

import java.time.Instant;
import java.util.List;

/**
 * 사고 분석 견적 리포트 (S15P21A307-336·337·338).
 *
 * <p><b>저장하지 않고 매번 조립한다.</b> 이미 저장된 사실을 모으는 것이라 가볍고, 리포트에 버전을
 * 두지 않는다 — 견적 버전이 곧 리포트 버전이다. 파일 이력은 PDF({@code estimate_report})가 갖는다.
 *
 * <p><b>새로 계산한 값이 없다.</b> 견적·근거·검증은 각 조회 API 의 응답을 그대로 담는다. 리포트와
 * 화면이 다른 숫자를 보이면 어느 쪽도 믿을 수 없게 된다.
 *
 * <p><b>{@code guidanceNotice} 는 {@link #checklist} 섹션에 붙는다.</b> {@code -488} 은 문구를 내보낼
 * 자리만 만들어 두고 "섹션이 생길 때 그 위에 붙게 한다" 고 미뤘다. 체크리스트 섹션이 생겨 PDF 는
 * 그 아래에 이 문구를 싣는다. 화면(FE)은 아직 섹션을 그리지 않는다.
 *
 * @param images      분석에 쓰인 사진. 분석에서 제외된 사진은 없다
 * @param validation  이 견적에 연결된 견적서 검증 결과. <b>없으면 null</b> — 화면·PDF 는 검증 섹션을
 *                    그리지 않는다(S15P21A307-338)
 * @param checklist   산정 근거로 싣는 정비 체크리스트. <b>{@code null} 이 아니다</b> — 없거나 만드는
 *                    중이면 상태와 빈 목록이다({@link Checklist})
 * @param legalNotice 고지 문구. 견적서 검증 화면·PDF 와 같은 문장이다(S15P21A307-289)
 * @param guidanceNotice 체크리스트·질문의 한계를 알리는 안내 고지(S15P21A307-487 · -488).
 *                    <b>없으면 null</b> 이고, 그래도 리포트는 나간다 — {@link #legalNotice} 와
 *                    강도가 다르다. 이유는 아래 주석 참고
 */
public record EstimateReportResponse(
        Vehicle vehicle,
        Accident accident,
        List<Image> images,
        EstimateResponse estimate,
        EstimateBasisResponse basis,
        ValidationResultResponse validation,
        Checklist checklist,
        String legalNotice,
        String guidanceNotice,
        Instant generatedAt) {

    public EstimateReportResponse {
        // 체크리스트는 부가 섹션이다. 비어 들어와도 리포트·PDF 템플릿이 NPE 로 죽지 않게 한다.
        checklist = checklist == null ? Checklist.NOT_REQUESTED : checklist;
    }

    /** 사고 접수 당시 차량. 이후 차량을 고치거나 지워도 바뀌지 않는다. */
    public record Vehicle(
            String manufacturer,
            String modelName,
            String vehicleType,
            String carClass,
            Short modelYear) {
    }

    public record Accident(Long accidentId, Instant createdAt) {
    }

    /**
     * @param overlayUrl 파손 부위를 표시한 분석 이미지의 조회 URL. <b>없거나 서명이 실패하면 null</b> —
     *                   화면·PDF 는 그 자리에 "분석 이미지 없음" 을 표시한다
     */
    public record Image(Long imageId, String angleCode, String overlayUrl) {
    }

    /**
     * 산정 근거로 싣는 정비 체크리스트.
     *
     * <h2>저장된 것을 읽기만 한다 — LLM 을 다시 부르지 않는다</h2>
     *
     * <p>체크리스트 항목은 워커가 LLM 을 <b>한 번</b> 불러 {@code repair_checklist_item} 에 저장해 두었다
     * ({@code RepairChecklistGenerator}). 리포트는 그 행을 옮겨 담을 뿐 다시 만들거나 "리포트용으로"
     * 다듬지 않는다 — 다듬는 순간 같은 체크리스트에 LLM 이 두 번 돈다. 그래서 문안은 {@code content}
     * 그대로다.
     *
     * <h2>{@code AI} 항목만 싣는다</h2>
     *
     * <ul>
     *   <li><b>{@code AI}</b> — 이 사고의 분석 결과(차량·손상 부위)에서 서버가 만든 항목이다. 산정 근거와
     *       같은 분석에서 나왔다. 안내 고지 문구도 "AI 분석 결과에 기반한 참고용 안내" 라고 적는다</li>
     *   <li><b>{@code COMMON} 은 뺀다</b> — 견적서 서면 수령·부품 등급 확인 같은, 어느 사고에나 똑같이
     *       붙는 절차 6종이다. 이 사고의 산정과 무관하고, 모든 리포트에 같은 여섯 줄이 찍힌다</li>
     *   <li><b>{@code USER} 는 뺀다</b> — 사용자가 직접 쓴 문장이다. 리포트는 보험사·정비소에 들고 가는
     *       문서라 사용자 메모가 서버가 낸 근거처럼 읽히면 안 된다</li>
     * </ul>
     *
     * <p><b>체크 여부·메모도 싣지 않는다.</b> 둘은 사용자의 진행 기록이라 클릭할 때마다 바뀐다 —
     * 실으면 같은 견적의 리포트가 체크 한 번에 달라지고, 메모는 PDF 에 인쇄돼 밖으로 나간다.
     *
     * <h2>없거나 만드는 중이어도 리포트는 뜬다</h2>
     *
     * <p>체크리스트는 워커가 비동기로 만든다. 상태를 그대로 주고 항목은 <b>{@code COMPLETED} 일 때만</b>
     * 채운다 — 그 판단은 {@code RepairChecklistStatusService} 가 이미 하고 있어 여기서 다시 하지 않는다.
     * 화면은 {@code status} 로 "없음 · 생성 중 · 실패" 를 가르고, PDF 는 항목이 있을 때만 섹션을 그린다.
     *
     * <h2>새로고침하면 바뀔 수 있다</h2>
     *
     * <p>리포트는 매번 조립하므로 체크리스트를 <b>재생성</b>하면 다음 조회부터 새 항목이 보인다.
     * 어느 생성분인지는 {@code generationNo} 로 알 수 있다. 리포트를 본 시점으로 고정하려면 저장이 필요하고
     * 그것은 스키마 변경이라 여기서 하지 않았다 — 한 시점으로 굳는 사본은 PDF 파일이다.
     *
     * @param status       {@code QUEUED · PROCESSING · COMPLETED · FAILED}. 생성을 요청한 적이 없으면 {@code null}
     * @param generationNo 몇 번째 생성분인가. 요청 전이면 0
     * @param completedAt  생성이 끝난 시각. {@code COMPLETED} 면 항목이 만들어진 시각, {@code FAILED} 면
     *                     실패한 시각, 그 전이면 {@code null}
     * @param items        {@code AI} 항목만, {@code display_order} 순. {@code COMPLETED} 가 아니면 빈 목록
     */
    public record Checklist(
            RepairChecklistStatus status,
            short generationNo,
            Instant completedAt,
            List<ChecklistItem> items) {

        /** 생성을 요청한 적이 없는 사고. 오류가 아니다. */
        public static final Checklist NOT_REQUESTED = new Checklist(null, (short) 0, null, List.of());

        public Checklist {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static Checklist from(RepairChecklistStatusResponse status) {
            List<ChecklistItem> items = status.items().stream()
                    .filter(item -> item.source() == RepairChecklistItemSource.AI)
                    .map(ChecklistItem::from)
                    .toList();
            return new Checklist(status.status(), status.generationNo(), status.completedAt(), items);
        }
    }

    /**
     * 체크리스트 항목 한 줄. <b>문안과 식별자뿐이다</b> — 체크 여부·메모·출처를 담을 자리가 없는 것이
     * 설계다({@link Checklist}).
     *
     * @param itemId  화면이 체크리스트 화면의 같은 항목으로 이어 줄 때 쓴다
     * @param content 생성 시점 문안 그대로
     */
    public record ChecklistItem(Long itemId, String content) {

        static ChecklistItem from(RepairChecklistItemResponse item) {
            return new ChecklistItem(item.itemId(), item.content());
        }
    }
}
