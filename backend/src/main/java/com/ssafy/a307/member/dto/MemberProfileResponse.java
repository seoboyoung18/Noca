package com.ssafy.a307.member.dto;

import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;

import java.time.Instant;

/**
 * 마이페이지가 쓰는 내 정보. 조회와 닉네임 수정이 같은 모양을 돌려준다 —
 * 수정 후 프론트가 화면을 다시 그리려고 조회를 한 번 더 부르지 않아도 되게.
 *
 * @param email           소셜에서 이메일을 받지 못하면 null 이다. 카카오는
 *                        {@code profile_nickname}, 구글은 {@code profile} 만 요청하므로
 *                        <b>현재는 두 provider 모두 null</b> 이다.
 * @param profileImageUrl service 버킷의 presigned GET URL. <b>유효 기간이 있어 매번 값이
 *                        달라진다</b> — 캐시 키로 쓰거나 저장해 두면 안 된다. 이미지가 없거나
 *                        저장소가 구성되지 않았으면 null 이고, 화면은 기본 이미지를 쓴다
 * @param vehicleCount    폐차·매각한 차량은 뺀 수. 차량 목록 화면과 숫자가 같아야 한다
 * @param accidentCount   폐차·매각한 차량의 사고도 포함한 수. 사고 이력 목록과 기준이 같다
 */
public record MemberProfileResponse(
        Long memberId,
        String nickname,
        String email,
        Provider provider,
        String profileImageUrl,
        long vehicleCount,
        long accidentCount,
        Instant createdAt) {

    public static MemberProfileResponse of(Member member, String profileImageUrl,
                                           long vehicleCount, long accidentCount) {
        return new MemberProfileResponse(
                member.getMemberId(),
                member.getNickname(),
                member.getEmail(),
                member.getProvider(),
                profileImageUrl,
                vehicleCount,
                accidentCount,
                member.getCreatedAt());
    }
}
