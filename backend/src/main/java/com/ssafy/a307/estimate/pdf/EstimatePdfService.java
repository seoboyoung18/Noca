package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.dto.EstimatePdfStatusResponse;
import com.ssafy.a307.estimate.pdf.EstimatePdfRepository.ReportView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 견적 PDF 요청·상태·다운로드 (S15P21A307-341·342).
 *
 * <p><b>생성은 하지 않는다.</b> 요청은 큐에 행을 넣고 202 로 끝나며, 만드는 것은 워커다
 * ({@link EstimatePdfWorker}). 워커를 끈 환경에서도 요청·상태 조회는 동작한다 — 요청이 QUEUED 로 남을 뿐이다.
 *
 * <p>소유자 검사는 견적 조회와 같은 경로를 탄다. 남의 견적과 없는 견적은 같은 404 다.
 */
@Service
@RequiredArgsConstructor
public class EstimatePdfService {

    /** 명세 — 다운로드 URL 은 5분. 새 나가도 노출 창이 좁다. */
    static final Duration DOWNLOAD_URL_VALIDITY = Duration.ofMinutes(5);

    private final EstimatePdfRepository pdfRepository;
    private final Optional<EstimatePdfStoragePort> storage;

    /**
     * 생성 요청. 재생성할 때마다 새 번호가 발급된다(견적 1 : 리포트 N) — 이전 파일은 이력으로 남는다.
     *
     * <p>순서가 중요하다. <b>잠금을 먼저 잡고</b> 그 안에서 진행 중 여부를 본다 — 잠금 밖에서 보면
     * 두 요청이 모두 "없음" 을 보고 들어와 {@code ux_er_inflight} 에서 한쪽이 500 대신 제약 위반으로 떨어진다.
     */
    @Transactional
    public EstimatePdfStatusResponse request(Long estimateId, Long memberId) {
        requireOwned(estimateId, memberId);

        LocalDate day = EstimatePdfKeys.issueDay(Instant.now());
        pdfRepository.lockIssuance(EstimatePdfKeys.issuanceLockKey(day));

        if (pdfRepository.existsInFlight(estimateId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 생성 중인 PDF가 있습니다.");
        }
        String last = pdfRepository.findMaxReportNo(EstimatePdfKeys.reportNoPrefix(day));
        String reportNo = EstimatePdfKeys.reportNo(day, EstimatePdfKeys.nextSequence(last));
        pdfRepository.insertQueued(estimateId, reportNo);

        return pdfRepository.findLatest(estimateId)
                .map(EstimatePdfStatusResponse::from)
                .orElseThrow(() -> new IllegalStateException("방금 넣은 리포트를 찾지 못했다: " + reportNo));
    }

    /** 가장 최근 요청의 상태. 요청한 적이 없으면 {@code null} 이다 — 오류가 아니다. */
    @Transactional(readOnly = true)
    public EstimatePdfStatusResponse status(Long estimateId, Long memberId) {
        requireOwned(estimateId, memberId);
        return pdfRepository.findLatest(estimateId)
                .map(EstimatePdfStatusResponse::from)
                .orElse(null);
    }

    /**
     * 최신 완료본의 조회 URL. 재생성 중이어도 이전 완료본을 준다.
     *
     * <p>완료본이 없으면 409 다 — 검증 PDF 다운로드와 같은 규칙이다.
     */
    @Transactional(readOnly = true)
    public URI download(Long estimateId, Long memberId) {
        requireOwned(estimateId, memberId);
        ReportView completed = pdfRepository.findLatestCompleted(estimateId)
                .filter(view -> view.getStorageKey() != null && !view.getStorageKey().isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "완료된 PDF가 없습니다."));

        return storage
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE, "PDF 보관소가 준비되지 않았습니다."))
                .createPresignedDownloadUrl(
                        completed.getStorageKey(),
                        DOWNLOAD_URL_VALIDITY,
                        EstimatePdfKeys.downloadFilename(completed.getReportNo()));
    }

    private void requireOwned(Long estimateId, Long memberId) {
        if (!pdfRepository.existsOwnedEstimate(estimateId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 견적입니다.");
        }
    }
}
