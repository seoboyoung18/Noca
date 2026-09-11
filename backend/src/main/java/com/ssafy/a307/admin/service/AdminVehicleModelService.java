package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.dto.VehicleModelAdminResponse;
import com.ssafy.a307.admin.dto.VehicleModelCreateRequest;
import com.ssafy.a307.admin.dto.VehicleModelUpdateRequest;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.entity.VehicleType;
import com.ssafy.a307.vehicle.repository.VehicleModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 차량 모델 마스터 관리.
 *
 * <p><b>물리 삭제 API 가 없다.</b> {@code vehicle.model_id} 가 {@code ON DELETE RESTRICT} 로
 * 이 행을 참조하고, 사고 스냅샷도 {@code snapshot_model_id} 로 가리킨다. 지우면 과거 차량과
 * 사고 이력이 무엇이었는지 말할 수 없게 된다 — 쓰지 않을 모델은 비활성화한다.
 *
 * <p><b>비활성화는 공개 목록에만 영향을 준다.</b> {@code VehicleModelService.findAllActive()} 와
 * {@code VehicleRegistrationService} 가 활성 조건으로 조회하므로 신규 등록 후보에서 빠지고,
 * 이미 등록된 차량과 사고는 그대로 조회된다.
 */
@Service
@RequiredArgsConstructor
public class AdminVehicleModelService {

    private static final Set<String> SORTABLE =
            Set.of("manufacturer", "modelName", "vehicleType", "carClass", "modelId");
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("manufacturer"), Sort.Order.asc("modelName"));

    private final VehicleModelRepository repository;
    private final AuditLogService auditLog;

    @Transactional(readOnly = true)
    public AdminPageResponse<VehicleModelAdminResponse> search(
            String keyword, String manufacturer, Boolean active,
            Integer page, Integer size, String sort) {

        return search(keyword, manufacturer, null, null, active, page, size, sort);
    }

    /**
     * @param carClass 응답 JSON 과 <b>같은 표기</b>로 받는다 — {@code CityCar}·{@code Compact}·
     *                 {@code Mid-size}·{@code Full-size}. enum 이름({@code MID_SIZE})으로 받으면 FE 가
     *                 목록에서 본 값을 필터로 되돌려 보낼 때 400 을 맞는다
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<VehicleModelAdminResponse> search(
            String keyword, String manufacturer, VehicleType vehicleType, String carClass,
            Boolean active, Integer page, Integer size, String sort) {

        return AdminPageResponse.of(
                repository.searchForAdmin(blankToNull(keyword), blankToNull(manufacturer),
                        vehicleType, carClassOrNull(carClass), active,
                        AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE)),
                VehicleModelAdminResponse::from);
    }

    @Transactional(readOnly = true)
    public VehicleModelAdminResponse findOne(Long modelId) {
        return VehicleModelAdminResponse.from(load(modelId));
    }

    @Transactional
    public VehicleModelAdminResponse create(VehicleModelCreateRequest request) {
        requireNoDuplicate(request.manufacturer(), request.modelName());

        VehicleModel saved = repository.saveAndFlush(VehicleModel.register(
                request.manufacturer(), request.modelName(),
                request.vehicleType(), request.carClass()));

        VehicleModelAdminResponse after = VehicleModelAdminResponse.from(saved);
        auditLog.created(AuditTargetType.VEHICLE_MODEL, String.valueOf(saved.getModelId()), after);
        return after;
    }

    @Transactional
    public VehicleModelAdminResponse update(Long modelId, VehicleModelUpdateRequest request) {
        VehicleModel model = load(modelId);
        requireCurrentVersion(model.getVersion(), request.version());

        // 이름이 실제로 바뀔 때만 중복을 본다 — 자기 자신과의 충돌을 중복으로 세지 않기 위해서다.
        boolean nameChanged = !model.getManufacturer().equals(request.manufacturer())
                || !model.getModelName().equals(request.modelName());
        if (nameChanged) {
            requireNoDuplicate(request.manufacturer(), request.modelName());
        }

        VehicleModelAdminResponse before = VehicleModelAdminResponse.from(model);
        model.modify(request.manufacturer(), request.modelName(),
                request.vehicleType(), request.carClass());
        repository.flush();

        VehicleModelAdminResponse after = VehicleModelAdminResponse.from(model);
        auditLog.updated(AuditTargetType.VEHICLE_MODEL, String.valueOf(modelId), before, after);
        return after;
    }

    @Transactional
    public VehicleModelAdminResponse changeStatus(Long modelId, StatusUpdateRequest request) {
        VehicleModel model = load(modelId);
        requireCurrentVersion(model.getVersion(), request.version());

        // 같은 상태를 다시 요청하면 아무것도 하지 않는다. 이력에 "껐다" 가 두 번 남으면
        // 두 번째는 거짓이 되고, 언제 실제로 꺼졌는지 찾을 수 없게 된다. 엔티티를 건드리지
        // 않으므로 version 도 오르지 않아 남의 화면이 괜히 버전 충돌을 만나지 않는다.
        if (model.isActive() == request.active()) {
            return VehicleModelAdminResponse.from(model);
        }

        VehicleModelAdminResponse before = VehicleModelAdminResponse.from(model);
        model.changeStatus(request.active());
        repository.flush();

        VehicleModelAdminResponse after = VehicleModelAdminResponse.from(model);
        auditLog.statusChanged(AuditTargetType.VEHICLE_MODEL, String.valueOf(modelId),
                before, after, request.active());
        return after;
    }

    private VehicleModel load(Long modelId) {
        return repository.findById(modelId)
                .orElseThrow(() -> AdminOperationException.notFound("차량 모델"));
    }

    /** 비활성 모델과도 겹칠 수 없다 — 같은 이름 두 행이 생기면 어느 쪽을 켜야 할지 알 수 없다. */
    private void requireNoDuplicate(String manufacturer, String modelName) {
        if (repository.existsByManufacturerAndModelName(manufacturer, modelName)) {
            throw new AdminOperationException(AdminErrorCode.DUPLICATE_VEHICLE_MODEL,
                    "이미 등록된 차량 모델입니다. (%s %s)".formatted(manufacturer, modelName));
        }
    }

    /**
     * 요청이 들고 온 버전이 지금 값과 같은지 먼저 본다.
     * <p>
     * {@code @Version} 만 믿으면 <b>같은 트랜잭션에서 읽은 엔티티를 그대로 저장</b>하므로
     * 충돌이 감지되지 않는다. 클라이언트가 읽은 시점의 버전과 대조하는 것이 실제 방어다.
     */
    private void requireCurrentVersion(long current, Long requested) {
        if (requested == null || current != requested) {
            throw new AdminOperationException(AdminErrorCode.VERSION_CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 다시 조회한 뒤 시도해 주세요. (현재 version %d)"
                            .formatted(current));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 모르는 표기를 조용히 무시하면 필터가 풀린 전체 목록이 나가 관리자가 오해한다. 400 이다. */
    private static CarClass carClassOrNull(String code) {
        String stripped = blankToNull(code);
        if (stripped == null) return null;
        try {
            return CarClass.fromCode(stripped);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "carClass 는 CityCar·Compact·Mid-size·Full-size 중 하나여야 합니다.");
        }
    }
}
