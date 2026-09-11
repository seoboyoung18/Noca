package com.ssafy.a307.repaircase.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.repaircase.config.RepairCaseImageProperties;
import com.ssafy.a307.repaircase.dto.RepairCaseDetailResponse;
import com.ssafy.a307.repaircase.dto.RepairCaseDetailResponse.Image;
import com.ssafy.a307.repaircase.dto.RepairCaseDetailResponse.Item;
import com.ssafy.a307.repaircase.dto.RepairCaseDetailResponse.Part;
import com.ssafy.a307.repaircase.image.RepairCaseImageStoragePort;
import com.ssafy.a307.repaircase.repository.RepairCaseDetailRepository;
import com.ssafy.a307.repaircase.repository.RepairCaseDetailRepository.CaseView;
import com.ssafy.a307.repaircase.repository.RepairCaseDetailRepository.ItemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 유사 사례 상세 (S15P21A307-241).
 *
 * <p><b>소유자 검사가 없다.</b> AI-Hub 사례는 사용자 데이터가 아니라 공개 데이터셋이라 누구의
 * 것도 아니다. 로그인만 요구하고, 사용자 데이터가 될 수 있는 {@code SERVICE} 사례는 쿼리에서
 * 막는다({@link RepairCaseDetailRepository#findPublicCase}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairCaseDetailService {

    private static final String NOT_APPROVED = "NOT_APPROVED";
    private static final String ANCILLARY = "ANCILLARY";
    private static final String NOT_APPROVED_WORK_NAME = "불인정";

    private final RepairCaseDetailRepository repository;
    private final RepairCaseImageProperties imageProperties;
    private final Optional<RepairCaseImageStoragePort> imageStorage;

    @Transactional(readOnly = true)
    public RepairCaseDetailResponse detail(Long caseId) {
        CaseView found = repository.findPublicCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 사례입니다."));

        List<Image> images = repository.findSearchableImages(caseId).stream()
                .map(image -> new Image(imageUrl(image.getStorageKey())))
                .toList();

        Map<String, List<ItemView>> byPart = new LinkedHashMap<>();
        List<Item> ancillaryItems = new ArrayList<>();
        Map<String, String> partNames = new LinkedHashMap<>();
        for (ItemView row : repository.findItems(caseId)) {
            if (ANCILLARY.equals(row.getLineType())) {
                ancillaryItems.add(toItem(row));
                continue;
            }
            byPart.computeIfAbsent(row.getPartCode(), code -> new ArrayList<>()).add(row);
            partNames.putIfAbsent(row.getPartCode(), row.getPartNameKo());
        }

        List<Part> parts = byPart.entrySet().stream()
                .map(entry -> toPart(entry.getKey(), partNames.get(entry.getKey()), entry.getValue()))
                .toList();

        return new RepairCaseDetailResponse(
                found.getCaseId(),
                found.getManufacturer(),
                found.getModelName(),
                found.getCarClass(),
                found.getRepairYear(),
                totalCost(found),
                images,
                parts,
                List.copyOf(ancillaryItems));
    }

    /**
     * 정비소 청구 기준으로 통일한다(요구사항 48행). AS 는 총계, SC 는 손해사정 전 청구액이다 —
     * SC 의 지급액({@code paid_amount})은 보험 지급 기준이라 AS 와 나란히 놓으면 비교가 틀어진다.
     */
    private static Integer totalCost(CaseView found) {
        return "AIHUB_SC".equals(found.getSource()) ? found.getClaimAmount() : found.getTotalCost();
    }

    /** 부위 합계. <b>불인정 행은 뺀다</b> — 손해사정에서 인정되지 않은 비용이다. */
    private static Part toPart(String partCode, String partNameKo, List<ItemView> rows) {
        long partTotal = rows.stream()
                .filter(row -> !NOT_APPROVED.equals(row.getAssessmentStatus()))
                .mapToLong(row -> row.getItemTotal() == null ? 0 : row.getItemTotal())
                .sum();
        List<Item> items = rows.stream().map(RepairCaseDetailService::toItem).toList();
        return new Part(partCode, partNameKo, partTotal, items);
    }

    /**
     * 불인정 행은 원천이 {@code 작업} 필드를 {@code 불인정} 으로 덮어써 원래 작업을 복구할 수 없다.
     * 적재가 그 원문을 {@code assessment_status} 로 옮겨 두었으므로 여기서 되돌려 보여 준다.
     */
    private static Item toItem(ItemView row) {
        boolean notApproved = NOT_APPROVED.equals(row.getAssessmentStatus());
        return new Item(
                row.getWorkCode(),
                notApproved ? NOT_APPROVED_WORK_NAME : row.getWorkType(),
                notApproved,
                row.getHq(),
                row.getPartCost(),
                row.getPaintMaterialCost(),
                row.getLaborCost(),
                row.getItemTotal());
    }

    /**
     * 이미지 조회 URL. <b>실패해도 상세를 무너뜨리지 않는다</b> — 그 이미지만 빈다.
     * {@link SimilarCaseService} 와 같은 정책이다. 키는 로그에만 남긴다.
     */
    private String imageUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || imageStorage.isEmpty()) {
            return null;
        }
        try {
            return imageStorage.get()
                    .createPresignedDownloadUrl(storageKey, imageProperties.downloadUrlValidity())
                    .url()
                    .toString();
        } catch (RuntimeException e) {
            log.warn("사례 이미지 조회 URL 발급 실패 key={}", storageKey, e);
            return null;
        }
    }
}
