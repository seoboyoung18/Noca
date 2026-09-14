package com.ssafy.a307.analysis.repository;

import com.ssafy.a307.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    /**
     * 소유자의 작업만 찾는다.
     *
     * <p><b>소유자 조건을 쿼리에 넣는다.</b> 조회한 뒤 자바에서 비교하면 비교를 잊는 실수가
     * 조용히 통과한다. {@code analysis_job} 에는 회원이 없으므로
     * {@code analysis_job → accident → vehicle → member} 경로로 판정한다.
     *
     * <p>없는 작업과 남의 작업이 <b>모두 빈 값</b>으로 돌아온다. 호출자는 둘을 구분하지 말고
     * 404 로 응답해야 한다 — 403 은 그 작업이 존재한다는 사실을 알려준다.
     */
    @Query("""
            select j from AnalysisJob j
            join j.accident a
            join a.vehicle v
            where j.jobId = :jobId and v.memberId = :memberId
            """)
    Optional<AnalysisJob> findByJobIdAndMemberId(@Param("jobId") Long jobId,
                                                 @Param("memberId") Long memberId);

    /**
     * 한 사고의 분석 작업을 <b>최신 순</b>으로. 첫 원소가 화면이 보는 작업이다.
     *
     * <p><b>재분석하면 작업이 쌓인다.</b> 어느 것을 보여 줄지 정해야 하는데, 사용자가 마지막으로
     * 요청한 결과가 화면이 말해야 할 상태이므로 가장 최근 것을 본다.
     *
     * <p>정렬 2차 키가 {@code jobId} 인 이유 — {@code created_at} 은 {@code DEFAULT now()} 라
     * 같은 트랜잭션에서 만든 두 작업이 <b>같은 값</b>을 가질 수 있다. 그러면 순서가 매번 달라져
     * 화면 배지가 새로고침마다 바뀐다. {@code AccidentRepository} 의 목록 보완 쿼리도
     * <b>같은 정렬</b>을 쓴다 — 두 API 가 다른 작업을 가리키면 배지와 상세가 어긋난다.
     *
     * <p>소유자 조건을 쿼리에 넣는다. {@code analysis_job} 에는 회원이 없으므로
     * {@code analysis_job → accident → vehicle → member} 경로로 판정한다.
     *
     * @return 작업이 없으면 빈 목록. <b>사고가 없거나 남의 사고일 때도 빈 목록</b>이므로
     *         호출자는 사고 존재 여부를 따로 확인해야 한다
     */
    @Query("""
            select j from AnalysisJob j
            join j.accident a
            join a.vehicle v
            where a.accidentId = :accidentId and v.memberId = :memberId
            order by j.createdAt desc, j.jobId desc
            """)
    List<AnalysisJob> findByAccidentIdAndMemberId(@Param("accidentId") Long accidentId,
                                                  @Param("memberId") Long memberId);

    /**
     * 사고의 작업을 최신 순으로. <b>소유자 조건이 없다</b> — 호출자가 이미 소유를 확인한
     * 비동기 경로 전용이다({@code S15P21A307-460} 체크리스트 생성 워커).
     *
     * <p>워커는 요청한 사용자의 세션을 들고 있지 않다. 소유 판정은 접수 시점에
     * {@code RepairChecklistRequestService} 가 끝냈고, 워커는 자기가 선점한 체크리스트에 달린
     * 사고만 본다. <b>HTTP 경로에서 이 메서드를 부르지 말 것</b> — 남의 사고가 그대로 열린다.
     *
     * <p>{@code jobId} 2차 정렬 키는 {@link #findByAccidentIdAndMemberId} 와 맞춘 것이다.
     * {@code created_at} 이 같은 건이 있을 때 순서가 흔들리지 않게 한다.
     */
    List<AnalysisJob> findByAccident_AccidentIdOrderByCreatedAtDescJobIdDesc(Long accidentId);
}
