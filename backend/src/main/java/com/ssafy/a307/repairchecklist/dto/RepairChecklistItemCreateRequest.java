package com.ssafy.a307.repairchecklist.dto;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 사용자가 직접 넣는 항목 (S15P21A307-485 · 부위·분류 -544).
 *
 * <p><b>수정({@code PATCH})과 record 를 나눠 둔다.</b> 문안만 받는
 * {@link RepairChecklistItemContentRequest} 에 두 필드를 더하면, 수정 요청이 보낸
 * {@code category} 를 서버가 <b>조용히 무시하게</b> 된다 — 화면은 저장했다고 표시하는데
 * 값은 그대로인 그 상태가 이 저장소가 가장 피하는 모양이다.
 *
 * <p><b>{@code source} 는 받지 않는다.</b> 서버가 {@code USER} 로 고정한다.
 * {@code displayOrder} 도 받지 않는다 — 서버가 맨 뒤에 붙인다.
 *
 * <p>{@code reason} 도 받지 않는다. 그 열은 "왜 <b>AI 가</b> 이것도 보라고 했는가" 를 적는
 * 자리다. 사용자가 적고 싶은 말은 메모({@code S15P21A307-483})가 받는다.
 *
 * @param content  항목 문안. 비어 있으면 400 이다 — 빈 줄을 체크리스트에 넣을 이유가 없다.
 *                 길이 상한은 검증하지 않고 {@code RepairChecklistItem} 이 잘라서 저장한다
 * @param category 없으면 {@code PART} 다. 값이 셋 중 하나가 아니면 400
 * @param partCode 부위. <b>마스터에 없는 코드는 400</b> 이다 — 사용자가 고른 값이라
 *                 조용히 비우면 화면이 넣었다고 표시한 부위가 사라진다
 *                 (LLM 이 지어낸 코드를 {@code null} 로 바꾸는 것과는 다른 판단이다)
 */
public record RepairChecklistItemCreateRequest(
        @NotBlank String content,
        RepairChecklistItemCategory category,
        @Size(max = 50, message = "partCode 는 50자를 넘을 수 없습니다.") String partCode) {
}
