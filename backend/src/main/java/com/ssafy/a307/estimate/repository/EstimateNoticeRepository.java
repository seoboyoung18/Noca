package com.ssafy.a307.estimate.repository;

import com.ssafy.a307.estimate.entity.EstimateNoticeContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 고지 문구 조회 (S15P21A307-288).
 *
 * <p>쓰기 메서드를 열어 두지 않는다 — {@link JpaRepository} 가 {@code save} 를 물려주지만
 * 호출하는 곳이 없고, 문구 변경은 {@code psql UPDATE} 로 한다
 * ({@link EstimateNoticeContent} 주석 참고).
 */
public interface EstimateNoticeRepository extends JpaRepository<EstimateNoticeContent, String> {

    /**
     * 내려보낼 문구 전부. 순서는 {@code display_order} 우선이고, 같으면 코드로 갈린다 —
     * 정렬이 흔들리면 같은 견적을 두 번 열었을 때 문구 순서가 달라 보인다.
     */
    List<EstimateNoticeContent> findByActiveTrueOrderByDisplayOrderAscCodeAsc();

    /** 코드 하나. 비활성 행은 없는 것으로 본다 — 내려 둔 문구가 리포트에만 새어 나가면 안 된다. */
    Optional<EstimateNoticeContent> findByCodeAndActiveTrue(String code);
}
