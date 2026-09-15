package com.ssafy.a307.repairchecklist.dto;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import jakarta.validation.constraints.Size;

/**
 * 항목 메모 (S15P21A307-483).
 *
 * <p><b>체크와 분리된 엔드포인트다.</b> {@code memo} 는 nullable 이고 {@code is_checked} 와
 * 제약으로 묶여 있지 않다 — 체크하지 않은 항목에도 메모를 남길 수 있다. 하나의 {@code PATCH} 로
 * 둘을 함께 받으면 <b>"보내지 않음" 과 "지워 달라" 가 둘 다 {@code null}</b> 이라 구분되지 않고,
 * 그러면 메모를 지우는 경로가 없어진다.
 *
 * @param memo {@code null} 이나 공백이면 메모를 지운다. {@code memo VARCHAR(500)} 이라
 *             500자를 넘기면 400 이다 — 조용히 잘라 저장하면 사용자가 쓴 뒷부분이 말없이 사라진다
 */
public record RepairChecklistItemMemoRequest(
        @Size(max = RepairChecklistItem.MAX_MEMO_LENGTH) String memo) {
}
