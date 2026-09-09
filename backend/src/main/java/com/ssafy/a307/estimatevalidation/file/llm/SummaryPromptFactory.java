package com.ssafy.a307.estimatevalidation.file.llm;

import com.ssafy.a307.estimatevalidation.file.SummaryGenerationPort;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 요약 지시문을 만든다.
 *
 * <p><b>모델에게 판단을 맡기지 않는다.</b> 등급·총액·검토 권장 항목은 규칙 엔진이 이미 정한
 * 값이고, 모델이 하는 일은 <b>그 값을 한국어 문장으로 옮기는 것뿐</b>이다. 그래서 지시문에
 * 계산 결과를 전부 적어 주고 "새로 만들지 말라" 고 못 박는다.
 *
 * <p>{@code LEGAL_NOTICE} 의 취지대로 <b>특정 사업자를 평가하는 표현을 금지</b>한다.
 * 이 결과는 참고용 추정치이고 정비소를 평가하는 도구가 아니다.
 */
final class SummaryPromptFactory {

    /** 지시문에 실을 근거 항목 수 상한. 전부 넣으면 지시문이 길어지고 비용이 늘어난다. */
    private static final int MAX_ISSUES = 20;

    private SummaryPromptFactory() {
    }

    static String instruction(SummaryGenerationPort.SummaryRequest request, String gradeDisplayName) {
        return """
                당신은 자동차 수리 견적 검증 결과를 사용자에게 설명하는 도구입니다.
                아래는 이미 계산이 끝난 결과입니다. 이 값을 한국어 두세 문장으로 옮겨 주세요.

                판정 등급: %s
                정비소 견적 총액: %,d원
                확인을 권장하는 항목 수: %d개

                확인 권장 항목:
                %s

                규칙:
                - 위에 적힌 값만 사용하세요. 금액, 등급, 항목 수를 새로 계산하거나 바꾸지 마세요.
                - 위에 없는 사실을 덧붙이지 마세요. 추측하지 마세요.
                - 정비소나 특정 업체를 평가하거나 비난하는 표현을 쓰지 마세요.
                  이 결과는 참고용 추정치이며 사업자를 평가하지 않습니다.
                - 확정적인 표현("바가지입니다", "사기입니다")을 쓰지 마세요.
                  확인을 권한다는 뜻으로만 쓰세요.
                - 두세 문장, 300자 이내로 씁니다.
                - 인사말, 머리말, 목록, 마크다운 없이 문장만 출력하세요.
                """.formatted(
                gradeDisplayName,
                request.claimedTotal(),
                request.issues().size(),
                issueLines(request.issues()));
    }

    private static String issueLines(List<SummaryGenerationPort.SummaryIssue> issues) {
        if (issues.isEmpty()) {
            return "- 없음";
        }
        String body = issues.stream()
                .limit(MAX_ISSUES)
                .map(issue -> "- %d행 %s: %s".formatted(issue.lineNo(), issue.itemName(), issue.reason()))
                .collect(Collectors.joining("\n"));
        return issues.size() > MAX_ISSUES
                ? body + "\n- (외 %d건)".formatted(issues.size() - MAX_ISSUES)
                : body;
    }
}
