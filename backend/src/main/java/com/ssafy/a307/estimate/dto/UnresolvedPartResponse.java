package com.ssafy.a307.estimate.dto;

/**
 * 산정하지 못해 총액에서 뺀 부위 하나 (S15P21A307-534).
 *
 * <p>화면과 PDF 는 이 목록이 비어 있지 않으면 "총액이 이 부위를 빼고 계산됐다" 는 사실을 함께
 * 보여 줘야 한다 — AI 연동 계약(2026-09-17)이 부분 견적의 {@code totals} 를 산정한 항목만으로
 * 합산하기 때문이다.
 *
 * @param partNameKo        한글 부위명. {@code part_code} 마스터에서 붙인다. 마스터에서 행이
 *                          사라졌으면 {@code null} 이다 — 그때는 {@code partCode} 를 보여 준다
 * @param damageType        {@code Scratched} · {@code Separated} · {@code Crushed} · {@code Breakage} · {@code null}
 * @param reason            사유 코드. 지금은 {@code INSUFFICIENT_CASES} 하나다
 * @param reasonDisplayName 사유 표시 문구. 모르는 코드면 {@code null} 이다
 */
public record UnresolvedPartResponse(
        String partCode,
        String partNameKo,
        String damageType,
        String reason,
        String reasonDisplayName) {
}
