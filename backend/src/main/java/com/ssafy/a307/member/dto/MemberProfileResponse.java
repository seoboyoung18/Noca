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
 * @param profileImageUrl 프로필 이미지 업로드(S15P21A307-89 잔여분)가 붙기 전까지 항상 null.
 *                        {@code member.profile_image_key} 컬럼은 있지만 채우는 경로가 없다.
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

    public static MemberProfileResponse of(Member member, long vehicleCount, long accidentCount) {
        return new MemberProfileResponse(
                member.getMemberId(),
                member.getNickname(),
                member.getEmail(),
                member.getProvider(),
                null,
                vehicleCount,
                accidentCount,
                member.getCreatedAt());
    }
}
