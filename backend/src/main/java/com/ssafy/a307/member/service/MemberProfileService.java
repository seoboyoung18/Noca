package com.ssafy.a307.member.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.member.dto.MemberProfileResponse;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    private final ProfileImageService profileImageService;
    private final VehicleRepository vehicleRepository;
    private final AccidentRepository accidentRepository;

    @Transactional(readOnly = true)
    public MemberProfileResponse profile(Long memberId) {
        return assemble(memberService.activeMember(memberId));
    }

    /**
     * 수정 후 갱신된 프로필을 그대로 돌려준다. 프론트가 화면을 다시 그리려고
     * 조회를 한 번 더 부르지 않아도 된다.
     */
    @Transactional
    public MemberProfileResponse changeNickname(Long memberId, String nickname) {
        return assemble(memberService.changeNickname(memberId, nickname));
    }

    /** 이미지 등록·변경·삭제도 갱신된 프로필을 돌려준다 — 닉네임 수정과 같은 이유다. */
    @Transactional
    public MemberProfileResponse changeProfileImage(Long memberId, String uploadKey) {
        return assemble(profileImageService.complete(memberId, uploadKey));
    }

    @Transactional
    public MemberProfileResponse deleteProfileImage(Long memberId) {
        return assemble(profileImageService.delete(memberId));
    }

    /**
     * 탈퇴. 익명화는 트랜잭션 안에서, <b>S3 파일 삭제는 트랜잭션 밖 best-effort</b> 로 한다 —
     * 명세가 정한 순서다.
     * <p>
     * <b>이 메서드에 {@code @Transactional} 을 붙이면 안 된다.</b> 붙이면 S3 삭제가
     * 트랜잭션 안으로 들어와, 네트워크 호출이 DB 커넥션을 붙들고 있게 되고 삭제 실패가
     * 탈퇴 롤백으로 번진다. 지금은 {@code memberService.withdraw} 가 자기 트랜잭션을
     * 열고 닫은 뒤에야 파일을 지운다.
     */
    public void withdraw(Long memberId, Instant now) {
        String removedImageKey = memberService.withdraw(memberId, now);
        profileImageService.deleteObjectQuietly(removedImageKey);
    }

    private MemberProfileResponse assemble(Member member) {
        return MemberProfileResponse.of(
                member,
                profileImageService.downloadUrlOrNull(member),
                vehicleRepository.countActiveByMemberId(member.getMemberId()),
                accidentRepository.countByMemberId(member.getMemberId()));
    }
}
