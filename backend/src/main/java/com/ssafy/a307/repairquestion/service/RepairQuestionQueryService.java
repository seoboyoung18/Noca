package com.ssafy.a307.repairquestion.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.repairquestion.dto.RepairQuestionItemResponse;
import com.ssafy.a307.repairquestion.dto.RepairQuestionResponse;
import com.ssafy.a307.repairquestion.entity.RepairQuestion;
import com.ssafy.a307.repairquestion.entity.RepairQuestionStatus;
import com.ssafy.a307.repairquestion.repository.RepairQuestionItemRepository;
import com.ssafy.a307.repairquestion.repository.RepairQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 질문 목록 조회 (S15P21A307-477). <b>조회만 한다</b> — 상태를 옮기는 코드가 없다.
 *
 * <p>없는 사고와 남의 사고는 <b>모두 404</b>, 사고는 있는데 아직 요청하지 않았으면
 * <b>빈 상태 200</b> 이다. 빈 상태를 404 로 내면 화면이 "없는 사고" 와 "생성 전" 을 구분하지
 * 못한다({@code RepairChecklistStatusService} · {@code AnalysisProgressService} 와 같은 형태다).
 *
 * <p><b>질문까지 함께 읽는다.</b> {@code S15P21A307-476} 의 전체 복사가 한 번의 조회로 끝나야
 * 하기 때문이다 — 이유는 {@link RepairQuestionResponse} 에 적어 두었다. 완성되지 않은 목록은
 * 조회하지 않는다. 질문 행은 {@code COMPLETED} 일 때만 의미가 있고,
 * {@code PROCESSING} 중에 읽으면 워커가 쓰는 중인 부분 결과가 보인다.
 */
@Service
@RequiredArgsConstructor
public class RepairQuestionQueryService {

    private final AccidentRepository accidentRepository;
    private final RepairQuestionRepository questionRepository;
    private final RepairQuestionItemRepository itemRepository;

    @Transactional(readOnly = true)
    public RepairQuestionResponse find(Long memberId, Long accidentId) {
        if (!accidentRepository.existsByAccidentIdAndMemberId(accidentId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다.");
        }

        return questionRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .map(question -> RepairQuestionResponse.from(question, items(question)))
                .orElseGet(RepairQuestionResponse::notRequested);
    }

    /** {@code ix_rqi_question (question_id, display_order)} 가 이 정렬을 그대로 커버한다. */
    private List<RepairQuestionItemResponse> items(RepairQuestion question) {
        if (question.getStatus() != RepairQuestionStatus.COMPLETED) {
            return List.of();
        }
        return itemRepository
                .findByQuestion_QuestionIdOrderByDisplayOrderAscItemIdAsc(question.getQuestionId())
                .stream()
                .map(RepairQuestionItemResponse::from)
                .toList();
    }
}
