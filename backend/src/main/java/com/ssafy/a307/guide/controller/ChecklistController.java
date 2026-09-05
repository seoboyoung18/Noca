package com.ssafy.a307.guide.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.guide.dto.ChecklistResponse;
import com.ssafy.a307.guide.service.ChecklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사고 현장 체크리스트. <b>인증이 필요 없다</b> — 사고 직후 로그인 없이 봐야 하는 화면이다.
 *
 * <p>{@code SecurityConfig} 가 {@code /api/guides/**} 를 이미 {@code permitAll} 로 열어 두었다.
 *
 * <p>회원을 특정하지 않으므로 {@code CurrentMemberProvider} 를 쓰지 않는다.
 * 완료 체크 상태도 저장하지 않는다 — 저장할 회원이 없다.
 */
@RestController
@RequestMapping("/api/guides")
@RequiredArgsConstructor
public class ChecklistController {

    private final ChecklistService checklistService;

    /** 사고 현장 체크리스트. 요청 파라미터 없음. */
    @GetMapping("/checklist")
    public ApiResponse<ChecklistResponse> getChecklist() {
        return ApiResponse.of(checklistService.getChecklist());
    }
}
