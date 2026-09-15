package com.ssafy.a307.repairchecklist.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemCheckRequest;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemMemoRequest;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemResponse;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.service.RepairChecklistItemService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistRequestService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정비 체크리스트 생성·조회·항목 기록 (S15P21A307-460 · -461 · -483).
 *
 * <p><b>사고 기준 경로다.</b> 화면이 손에 쥐고 있는 것은 {@code accidentId} 이고
 * {@code checklistId} 는 서버가 정하는 값이다. 사고당 한 행({@code uk_rcl_accident})이므로
 * 사고로 물어보면 충분하다 — {@code AnalysisProgressController} 와 같은 형태다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 *
 * <p><b>항목 추가·수정·삭제와 재생성은 여기 없다.</b> 각각 {@code S15P21A307-485} ·
 * {@code -486} 의 몫이다.
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/repair-checklist")
@RequiredArgsConstructor
public class RepairChecklistController {

    private final RepairChecklistRequestService requestService;
    private final RepairChecklistStatusService statusService;
    private final RepairChecklistItemService itemService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 생성 요청. <b>비동기라 접수만 하고 즉시 돌아온다</b> — 결과는 상태 조회로 본다.
     *
     * <p>없는 사고·남의 사고는 404, {@code GMS_KEY} 가 없는 환경은 503, 이미 완성된 체크리스트가
     * 있으면 409 다. 접수에 성공하면 항목이 빈 {@code QUEUED} 상태를 돌려준다.
     */
    @PostMapping
    public ApiResponse<RepairChecklistStatusResponse> request(@PathVariable Long accidentId) {
        return ApiResponse.of(requestService.request(
                currentMemberProvider.currentMemberId(), accidentId));
    }

    /**
     * 생성 상태 · 항목 · 진행률 · 고지 문구. 아직 요청하지 않은 사고는 <b>빈 상태 200</b> 이다.
     *
     * <p>항목은 {@code display_order} 순으로 한 번에 전부 준다. 나눠 주지 않는 이유는
     * {@link RepairChecklistStatusResponse} 에 적어 두었다.
     */
    @GetMapping
    public ApiResponse<RepairChecklistStatusResponse> status(@PathVariable Long accidentId) {
        return ApiResponse.of(statusService.status(
                currentMemberProvider.currentMemberId(), accidentId));
    }

    /**
     * 항목 완료 체크·해제 (S15P21A307-483).
     *
     * <p>없는 항목·남의 항목·다른 사고의 항목은 <b>모두 404</b> 다. 바뀐 항목 한 줄을 돌려주므로
     * 화면이 다시 조회하지 않아도 된다 — 진행률까지 필요하면 {@code GET} 을 부른다.
     */
    @PatchMapping("/items/{itemId}/check")
    public ApiResponse<RepairChecklistItemResponse> check(
            @PathVariable Long accidentId,
            @PathVariable Long itemId,
            @Valid @RequestBody RepairChecklistItemCheckRequest request) {
        return ApiResponse.of(itemService.changeChecked(
                currentMemberProvider.currentMemberId(), accidentId, itemId, request.checked()));
    }

    /**
     * 항목 메모 저장 (S15P21A307-483). <b>체크 여부를 건드리지 않는다.</b>
     *
     * <p>본문의 {@code memo} 가 비어 있으면 메모를 지운다. 체크와 경로를 나눈 이유는
     * {@link RepairChecklistItemMemoRequest} 에 적어 두었다.
     */
    @PatchMapping("/items/{itemId}/memo")
    public ApiResponse<RepairChecklistItemResponse> memo(
            @PathVariable Long accidentId,
            @PathVariable Long itemId,
            @Valid @RequestBody RepairChecklistItemMemoRequest request) {
        return ApiResponse.of(itemService.changeMemo(
                currentMemberProvider.currentMemberId(), accidentId, itemId, request.memo()));
    }
}
