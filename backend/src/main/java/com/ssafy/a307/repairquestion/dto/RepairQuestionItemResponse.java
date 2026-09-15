package com.ssafy.a307.repairquestion.dto;

import com.ssafy.a307.repairquestion.entity.RepairQuestionItem;
import com.ssafy.a307.repairquestion.entity.RepairQuestionItemSource;

/**
 * 질문 한 줄 (S15P21A307-476 개별 복사 · 전체 복사).
 *
 * <p><b>{@link #content} 만으로 복사가 끝나야 한다.</b> 화면이 부품 이름과 문장을 이어 붙여
 * 만들어야 한다면 그것은 조각으로 쪼개 둔 것이고, {@code S15P21A307-476} 이 금지한 모양이다.
 * 근거 네 값은 <b>표시 · 묶음용 부가 정보</b>이지 문장의 일부가 아니다.
 *
 * <h2>한글 라벨을 내려보내지 않는다</h2>
 *
 * <p>{@link #source} · {@link #damageType} · {@link #repairMethod} 는 전부 DDL 표기 그대로의
 * <b>코드</b>다. 화면 문구는 FE 가 정한다 — 서버가 라벨을 박으면 표현을 바꿀 때마다 배포해야 한다
 * ({@code AnalysisProgressResponse} 와 같은 규칙).
 *
 * <p>{@link #partName} 만 한글이지만 이것은 라벨이 아니라 <b>생성 시점 {@code part_code.name_ko}
 * 의 사본</b>이다({@code snapshot_part_name}). 마스터가 이름을 고쳐도 이미 만들어진 질문 문안과
 * 어긋나지 않게 하려고 저장해 둔 값이라, 지금 마스터를 다시 읽어 주면 그 목적이 사라진다.
 *
 * @param partCode   근거가 된 부품. 부품에 매이지 않은 질문이면 {@code null}
 * @param partName   {@code partCode} 와 <b>함께</b> 있거나 함께 없다({@code ck_rqi_basis})
 * @param damageType {@code partCode} 가 없으면 반드시 {@code null} 이다({@code ck_rqi_part})
 */
public record RepairQuestionItemResponse(
        Long itemId,
        RepairQuestionItemSource source,
        String content,
        short displayOrder,
        String partCode,
        String partName,
        String damageType,
        String repairMethod) {

    public static RepairQuestionItemResponse from(RepairQuestionItem item) {
        return new RepairQuestionItemResponse(
                item.getItemId(),
                item.getSource(),
                item.getContent(),
                item.getDisplayOrder(),
                item.getPartCode(),
                item.getSnapshotPartName(),
                item.getDamageType(),
                item.getRepairMethod());
    }
}
