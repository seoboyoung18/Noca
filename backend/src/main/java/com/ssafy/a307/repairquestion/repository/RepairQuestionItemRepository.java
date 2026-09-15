package com.ssafy.a307.repairquestion.repository;

import com.ssafy.a307.repairquestion.entity.RepairQuestionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RepairQuestionItemRepository extends JpaRepository<RepairQuestionItem, Long> {

    /** {@code ix_rqi_question (question_id, display_order)} 가 이 조회를 위해 있다. */
    List<RepairQuestionItem> findByQuestion_QuestionIdOrderByDisplayOrderAscItemIdAsc(Long questionId);

    /**
     * 생성분을 통째로 비운다. 실패한 건을 다시 만들 때 앞 시도가 남긴 질문이 섞이지 않게 한다.
     *
     * <p><b>파생 {@code deleteBy...} 가 아니라 벌크 DELETE 여야 한다.</b> 파생 삭제는 엔티티를
     * 하나씩 {@code remove} 표시만 해 두고 실제 DELETE 는 플러시 때 나가는데, <b>Hibernate 는 같은
     * 플러시에서 INSERT 를 DELETE 보다 먼저 내보낸다.</b> 체크리스트에서 그 순서가 실제로
     * {@code uk_rcli_common} 을 위반해 재시도를 통째로 깨뜨렸다(2026-09-14).
     *
     * <p>질문 쪽에는 그런 UNIQUE 가 없어 지금은 겹칠 제약이 없지만, 파생 삭제로 두면 앞 시도의
     * 행이 남은 채 새 행이 들어가 <b>한 목록에 두 시도가 섞인 순간</b>이 생긴다. 같은 이유로
     * 벌크 DELETE 를 쓴다.
     *
     * <p>{@code clearAutomatically} 는 쓰지 않는다 — 영속성 컨텍스트를 비우면 호출자가 들고 있던
     * {@code RepairQuestion} 이 준영속이 되어 이어지는 상태 전이가 저장되지 않는다.
     *
     * @return 지운 행 수
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RepairQuestionItem i where i.question.questionId = :questionId")
    int deleteAllByQuestionId(@Param("questionId") Long questionId);
}
