package com.ssafy.a307.repairchecklist.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.service.RepairChecklistRequestService;
import com.ssafy.a307.repairchecklist.service.RepairChecklistStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정비 체크리스트 생성·상태 (S15P21A307-460 · -461).
 *
 * <p><b>사고 기준 경로다.</b> 화면이 손에 쥐고 있는 것은 {@code accidentId} 이고
 * {@code checklistId} 는 서버가 정하는 값이다. 사고당 한 행({@code uk_rcl_accident})이므로
 * 사고로 물어보면 충분하다 — {@code AnalysisProgressController} 와 같은 형태다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 *
 * <p><b>항목 조회·체크·메모·재생성은 여기 없다.</b> 각각 {@code S15P21A307-483} · {@code -485} ·
 * {@code -482} · {@code -486} 의 몫이다.
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/repair-checklist")
@RequiredArgsConstructor
public class RepairChecklistController {

    private final RepairChecklistRequestService requestService;
    private final RepairChecklistStatusService statusService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 생성 요청. <b>비동기라 접수만 하고 즉시 돌아온다</b> — 결과는 상태 조회로 본다.
     *
     * <p>없는 사고·남의 사고는 404, {@code GMS_KEY} 가 없는 환경은 503, 이미 완성된 체크리스트가
     * 있으면 409 다. 접수에 성공하면 {@code QUEUED} 상태를 돌려준다.
     */
    @PostMapping
    public ApiResponse<RepairChecklistStatusResponse> request(@PathVariable Long accidentId) {
        return ApiResponse.of(requestService.request(
                currentMemberProvider.currentMemberId(), accidentId));
    }

    /** 생성 상태. 아직 요청하지 않은 사고는 <b>빈 상태 200</b> 이다. */
    @GetMapping
    public ApiResponse<RepairChecklistStatusResponse> status(@PathVariable Long accidentId) {
        return ApiResponse.of(statusService.status(
                currentMemberProvider.currentMemberId(), accidentId));
    }
}
