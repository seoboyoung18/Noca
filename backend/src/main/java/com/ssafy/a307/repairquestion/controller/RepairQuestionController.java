package com.ssafy.a307.repairquestion.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.repairquestion.dto.RepairQuestionResponse;
import com.ssafy.a307.repairquestion.service.RepairQuestionQueryService;
import com.ssafy.a307.repairquestion.service.RepairQuestionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정비소 확인 질문 생성·조회 (S15P21A307-476 · -477).
 *
 * <p><b>사고 기준 경로다.</b> 화면이 손에 쥐고 있는 것은 {@code accidentId} 이고
 * {@code questionId} 는 서버가 정하는 값이다. 사고당 한 행({@code uk_rq_accident})이므로 사고로
 * 물어보면 충분하다 — {@code RepairChecklistController} 와 같은 형태이고, 같은 화면
 * (정비 체크리스트 &gt; 질문 생성)에 나란히 붙는다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 *
 * <p><b>재생성·개별 삭제·사용자 추가는 여기 없다.</b> {@code S15P21A307-476} 은 생성과 복사만
 * 요구하고, 재생성을 위한 자리({@code generation_no}·{@code regenerated_at})는 스키마에 있지만
 * 그것을 쓰는 스토리는 아직 없다.
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/repair-questions")
@RequiredArgsConstructor
public class RepairQuestionController {

    private final RepairQuestionRequestService requestService;
    private final RepairQuestionQueryService queryService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 생성 요청. <b>비동기라 접수만 하고 즉시 돌아온다</b> — 결과는 조회로 본다.
     *
     * <p>없는 사고·남의 사고는 404, {@code GMS_KEY} 가 없는 환경은 503, 이미 완성된 질문 목록이
     * 있으면 409 다. 접수에 성공하면 질문이 빈 {@code QUEUED} 상태를 돌려준다.
     */
    @PostMapping
    public ApiResponse<RepairQuestionResponse> request(@PathVariable Long accidentId) {
        return ApiResponse.of(requestService.request(
                currentMemberProvider.currentMemberId(), accidentId));
    }

    /**
     * 생성 상태와 질문 목록. 아직 요청하지 않은 사고는 <b>빈 상태 200</b> 이다.
     *
     * <p>질문은 {@code display_order} 순으로 한 번에 전부 준다 — {@code S15P21A307-476} 의
     * 전체 복사가 여러 번 호출로 나뉘면 안 된다.
     */
    @GetMapping
    public ApiResponse<RepairQuestionResponse> find(@PathVariable Long accidentId) {
        return ApiResponse.of(queryService.find(
                currentMemberProvider.currentMemberId(), accidentId));
    }
}
