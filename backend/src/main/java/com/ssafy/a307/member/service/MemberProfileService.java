package com.ssafy.a307.member.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.member.dto.MemberProfileResponse;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지 응답을 조립한다 — 회원 정보에 차량·사고 건수를 붙인다.
 * <p>
 * <b>{@link MemberService} 에 합치지 않은 이유.</b> MemberService 는 소셜 로그인이
 * 지나가는 인증 경로다. 거기에 vehicle·accident 리포지토리를 달면 로그인이 두 도메인에
 * 묶여, 그쪽 변경이 로그인을 깨뜨릴 수 있다. 조회용 조립만 여기로 분리한다.
 */
@Service
@RequiredArgsConstructor
public class MemberProfileService {

    private final MemberService memberService;
    private final VehicleRepository vehicleRepository;
    private final AccidentRepository accidentRepository;

    @Transactional(readOnly = true)
    public MemberProfileResponse profile(Long memberId) {
        return withCounts(memberService.activeMember(memberId));
    }

    /**
     * 수정 후 갱신된 프로필을 그대로 돌려준다. 프론트가 화면을 다시 그리려고
     * 조회를 한 번 더 부르지 않아도 된다.
     */
    @Transactional
    public MemberProfileResponse changeNickname(Long memberId, String nickname) {
        return withCounts(memberService.changeNickname(memberId, nickname));
    }

    private MemberProfileResponse withCounts(Member member) {
        return MemberProfileResponse.of(
                member,
                vehicleRepository.countActiveByMemberId(member.getMemberId()),
                accidentRepository.countByMemberId(member.getMemberId()));
    }
}
