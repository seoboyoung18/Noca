package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemCategory;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistItemRepository;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 항목 완료 체크와 메모 저장 (S15P21A307-483).
 *
 * <h2>체크와 메모를 따로 받는다</h2>
 *
 * <p>{@code memo} 는 nullable 이고 {@code is_checked} 와 제약으로 묶여 있지 않다 — 체크하지 않은
 * 항목에도 메모를 남길 수 있다. 하나의 {@code PATCH} 로 둘을 함께 받으면 <b>"보내지 않음" 과
 * "지워 달라" 가 둘 다 {@code null}</b> 이라 구분되지 않고, 그러면 메모를 지우는 경로가 없어진다.
 *
 * <h2>{@code ck_rcli_checked} 를 어기지 않는다</h2>
 *
 * <p>{@code CHECK (checked_at IS NULL OR is_checked = TRUE)} — 체크는 두 열을 함께 쓰고, 해제는
 * {@code checked_at} 을 함께 비운다. <b>그 전이를 서비스가 직접 하지 않고</b>
 * {@link RepairChecklistItem#check} · {@link RepairChecklistItem#uncheck} 에 맡긴다. 열을 여기서
 * 따로 만지면 다른 경로가 하나 더 생길 때 제약에 걸려 500 이 난다.
 *
 * <h2>없는 항목 · 남의 항목 · 다른 사고의 항목을 모두 404 로 낸다</h2>
 *
 * <p>소유자 조건은 자바 비교가 아니라 <b>쿼리 조건</b>에 있다
 * ({@code RepairChecklistItemRepository#findOwnedItem}). 403 은 "그 항목은 존재한다" 는 사실을
 * 알려 준다.
 *
 * <h2>생성 상태를 검사하지 않는다</h2>
 *
 * <p>항목이 존재한다는 것 자체가 그 체크리스트가 {@code COMPLETED} 였다는 뜻이다 — 워커는
 * 완료 직전에만 항목을 넣고, 재시도는 앞 시도의 항목을 지우고 시작한다. 상태를 한 번 더 보면
 * 같은 판단이 두 곳에 생기고, 재생성({@code S15P21A307-486}) 중에 체크가 들어오는 경우의 정답도
 * 그쪽 스토리가 정할 일이다.
 */
@Service
@RequiredArgsConstructor
public class RepairChecklistItemService {

    private final RepairChecklistItemRepository itemRepository;
    private final RepairChecklistRepository checklistRepository;
    private final PartCodeRepository partCodeRepository;

    /**
     * 사용자가 항목을 직접 추가한다 (S15P21A307-485).
     *
     * <p><b>{@code source} 는 서버가 {@code USER} 로 고정한다.</b> 클라이언트가 정하지 못한다 —
     * {@code AI} 를 자처하면 "AI 가 이렇게 말했다" 는 기록이 거짓이 된다.
     *
     * <p>{@code displayOrder} 는 현재 최댓값 + 1 이라 <b>맨 뒤</b>에 붙는다. 항목이 하나도 없으면
     * 1 이다.
     *
     * <p><b>생성 상태를 보지 않는다.</b> 체크리스트가 있으면 넣을 수 있다 — 생성 중에 넣어도
     * {@code deleteGeneratedByChecklistId} 가 {@code USER} 를 남기므로 사라지지 않는다.
     * 다만 조회는 {@code COMPLETED} 일 때만 항목을 주므로(prompt70), 완성 전에 넣은 항목은
     * 완성된 뒤에야 보인다.
     */
    @Transactional
    public RepairChecklistItemResponse add(Long memberId, Long accidentId, String content,
                                           RepairChecklistItemCategory category, String partCode) {
        RepairChecklist checklist = checklistRepository
                .findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "체크리스트를 찾을 수 없습니다."));

        String known = requireKnownPart(partCode);
        int nextOrder = itemRepository.findMaxDisplayOrder(checklist.getChecklistId())
                .map(max -> max + 1)
                .orElse(1);

        RepairChecklistItem saved = itemRepository.save(RepairChecklistItem.user(
                checklist, content, category, known, nextOrder, Instant.now()));
        return response(saved);
    }

    /**
     * 부위를 보냈으면 마스터에 있어야 한다 (S15P21A307-544).
     *
     * <p><b>조용히 비우지 않는다.</b> LLM 이 지어낸 코드는 버리고 항목만 남기지만
     * ({@code RepairChecklistGenerator}), 이것은 사용자가 화면에서 고른 값이다 — 비우면
     * 넣었다고 표시된 부위가 다음 조회에서 사라진다.
     */
    private String requireKnownPart(String partCode) {
        if (partCode == null || partCode.isBlank()) {
            return null;
        }
        String stripped = partCode.strip();
        if (!partCodeRepository.existsById(stripped)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "알 수 없는 부품 코드입니다.");
        }
        return stripped;
    }

    /**
     * 사용자 항목의 문안을 고친다 (S15P21A307-485).
     *
     * <p><b>{@code AI}·{@code COMMON} 항목은 400 으로 거절한다.</b> 조용히 무시하지 않는다 —
     * 화면이 저장했다고 표시하는데 값이 안 바뀌면 그쪽이 더 나쁘다. 근거는
     * {@link RepairChecklistItem#changeContent} 에 적어 두었다.
     */
    @Transactional
    public RepairChecklistItemResponse changeContent(Long memberId, Long accidentId, Long itemId,
                                                     String content) {
        RepairChecklistItem item = userOwned(memberId, accidentId, itemId, "수정");
        item.changeContent(content, Instant.now());
        return response(item);
    }

    /**
     * 사용자 항목을 지운다 (S15P21A307-485).
     *
     * <p><b>{@code AI}·{@code COMMON} 항목은 400 으로 거절한다.</b> 공통 6종은 "파손 부위와
     * 무관한 필수 확인 사항"({@code S15P21A307-462})이라 사용자가 지우면 안내가 무너진다.
     * 필요 없으면 <b>체크하지 않고 두면 된다.</b>
     */
    @Transactional
    public void delete(Long memberId, Long accidentId, Long itemId) {
        itemRepository.delete(userOwned(memberId, accidentId, itemId, "삭제"));
    }

    /**
     * 항목을 완료로 표시하거나 해제한다.
     *
     * <p><b>토글이 아니다.</b> 같은 값을 두 번 보내면 같은 상태로 끝난다 — 정비소 현장에서
     * 통신이 불안정한 채로 쓰는 화면이라 재전송이 잦다.
     */
    @Transactional
    public RepairChecklistItemResponse changeChecked(Long memberId, Long accidentId, Long itemId,
                                                     boolean checked) {
        RepairChecklistItem item = owned(memberId, accidentId, itemId);
        Instant now = Instant.now();
        if (checked) {
            item.check(now);
        } else {
            item.uncheck(now);
        }
        return response(item);
    }

    /**
     * 메모를 저장한다. <b>체크 여부를 건드리지 않는다.</b>
     *
     * @param memo {@code null} 이나 공백이면 메모를 지운다
     */
    @Transactional
    public RepairChecklistItemResponse changeMemo(Long memberId, Long accidentId, Long itemId,
                                                  String memo) {
        RepairChecklistItem item = owned(memberId, accidentId, itemId);
        item.changeMemo(memo, Instant.now());
        return response(item);
    }

    /**
     * 한 줄 응답. 부위가 있으면 한글 이름을 마스터에서 붙인다 — 항목에 사본을 두지 않는
     * 설계라 이름은 조회 때 붙는다({@code RepairChecklistStatusService} 와 같은 규칙).
     */
    private RepairChecklistItemResponse response(RepairChecklistItem item) {
        String partCode = item.getPartCode();
        String nameKo = partCode == null ? null
                : partCodeRepository.findById(partCode).map(PartCode::getNameKo).orElse(null);
        return RepairChecklistItemResponse.from(item, nameKo);
    }

    private RepairChecklistItem owned(Long memberId, Long accidentId, Long itemId) {
        return itemRepository.findOwnedItem(itemId, accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "체크리스트 항목을 찾을 수 없습니다."));
    }

    /**
     * 사용자 항목이어야 하는 자리. <b>404 와 400 을 가른다.</b>
     *
     * <p>없는 항목·남의 항목·다른 사고의 항목은 <b>404</b> — 그 자원이 존재한다는 사실조차
     * 알려 주지 않는다. 항목은 찾았는데 {@code AI}·{@code COMMON} 이면 <b>400</b> 이다 — 그
     * 항목이 있다는 것은 이미 아는 사실이고, 막히는 이유가 "없어서" 가 아니라 "그런 항목은
     * 고칠 수 없어서" 라는 것을 알려 줘야 화면이 맞는 안내를 띄운다.
     */
    private RepairChecklistItem userOwned(Long memberId, Long accidentId, Long itemId,
                                          String action) {
        RepairChecklistItem item = owned(memberId, accidentId, itemId);
        if (!item.userCreated()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "직접 추가한 항목만 " + action + "할 수 있습니다.");
        }
        return item;
    }
}
