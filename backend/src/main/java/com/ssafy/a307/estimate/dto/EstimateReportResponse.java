package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;

import java.time.Instant;
import java.util.List;

/**
 * 사고 분석 견적 리포트 (S15P21A307-336·337·338).
 *
 * <p><b>저장하지 않고 매번 조립한다.</b> 이미 저장된 사실을 모으는 것이라 가볍고, 리포트에 버전을
 * 두지 않는다 — 견적 버전이 곧 리포트 버전이다. 파일 이력은 PDF({@code estimate_report})가 갖는다.
 *
 * <p><b>새로 계산한 값이 없다.</b> 견적·근거·검증은 각 조회 API 의 응답을 그대로 담는다. 리포트와
 * 화면이 다른 숫자를 보이면 어느 쪽도 믿을 수 없게 된다.
 *
 * <p><b>체크리스트 안내 고지를 싣지 않는다</b>(S15P21A307-533). 2026-09-17 에 리포트·견적 PDF 에
 * 체크리스트를 싣지 않기로 정해(S15P21A307-532) 그 고지도 가리킬 섹션이 없어졌다. 실어 두면 리포트
 * 화면에 "본 체크리스트와 질문은…" 이 체크리스트 없이 나온다. 고지는 체크리스트 화면이 체크리스트
 * 조회의 {@code notice} 로 보여 준다.
 *
 * @param images      분석에 쓰인 사진. 분석에서 제외된 사진은 없다
 * @param validation  이 견적에 연결된 견적서 검증 결과. <b>없으면 null</b> — 화면·PDF 는 검증 섹션을
 *                    그리지 않는다(S15P21A307-338)
 * @param legalNotice 고지 문구. 견적서 검증 화면·PDF 와 같은 문장이다(S15P21A307-289)
 * @param narrative   LLM 이 쓴 안내 문장 (S15P21A307-537). <b>없으면 null</b> — 아직 만들지
 *                    않았거나 생성이 실패한 견적이다. 화면·PDF 는 그 영역을 그리지 않는다
 */
public record EstimateReportResponse(
        Vehicle vehicle,
        Accident accident,
        List<Image> images,
        EstimateResponse estimate,
        EstimateBasisResponse basis,
        ValidationResultResponse validation,
        Narrative narrative,
        String legalNotice,
        Instant generatedAt) {

    /**
     * 리포트를 읽는 법을 말해 주는 문장 (S15P21A307-537).
     *
     * <p><b>숫자가 없다.</b> 금액·등급은 {@code estimate} 가 이미 들고 있고, 이 자리는 그것을
     * 사람이 읽을 문장으로 옮긴 것뿐이다 — 두 곳에 숫자가 있으면 어느 쪽이 맞는지 다투게 된다.
     *
     * @param summary  견적 전체를 어떻게 읽을지 한 문단
     * @param cautions 지금 확인해 두면 좋은 것. 없으면 빈 목록
     */
    public record Narrative(String summary, List<String> cautions) {

        public Narrative {
            cautions = cautions == null ? List.of() : List.copyOf(cautions);
        }
    }

    /** 사고 접수 당시 차량. 이후 차량을 고치거나 지워도 바뀌지 않는다. */
    public record Vehicle(
            String manufacturer,
            String modelName,
            String vehicleType,
            String carClass,
            Short modelYear) {
    }

    public record Accident(Long accidentId, Instant createdAt) {
    }

    /**
     * 분석에 쓴 사진 한 장.
     *
     * <p><b>사진은 {@code imageUrl} 로 그린다</b>(S15P21A307-560). 오버레이는 2026-09-11 에 폐기돼
     * {@code overlayUrl} 이 늘 {@code null} 이고, 그것만 보던 미리보기는 사진 칸이 통째로 비어
     * 있었다 — 같은 견적의 PDF 에는 사진이 나오는데. {@code imageUrl} 은 <b>PDF 와 같은 순서로</b>
     * 고른다: 오버레이가 있으면 그것, 없으면 사용자가 올린 사진(축소본). 미리보기에서 본 사진과
     * 받은 PDF 의 사진이 다르면 사용자는 어느 쪽을 믿어야 할지 모른다.
     *
     * @param overlayUrl 파손 부위를 표시한 분석 이미지의 조회 URL. 지금은 늘 {@code null} 이다.
     *                   이 값만 보던 화면이 깨지지 않게 남겨 둔다 — 새 화면은 {@code imageUrl} 을 쓴다
     * @param imageUrl   화면이 그릴 사진의 조회 URL(5분짜리 서명 URL). 오버레이도 축소본도 없거나
     *                   서명이 실패하면 {@code null} 이고, 그 칸은 비운다
     * @param overlay    {@code imageUrl} 이 분석 오버레이인가. {@code false} 면 사용자가 올린 사진이다 —
     *                   캡션이 "분석 표시 포함" 과 "업로드한 사진" 을 가른다. 파손 표시가 없는 사진을
     *                   "분석 이미지" 라고 부르면 없는 근거가 있는 것처럼 보인다
     * @param boxes      사진 위에 그릴 파손 위치. <b>PDF 가 그리는 것과 같은 값이다.</b> 오버레이이거나
     *                   좌표·사진 크기를 모르면 빈 목록이고 사진만 그린다
     */
    public record Image(Long imageId, String angleCode, String overlayUrl,
                        String imageUrl, boolean overlay, List<Box> boxes) {

        public Image {
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
        }

        /** 오버레이만 있던 시절의 모양. 오버레이가 곧 그릴 사진이고 박스는 없다. */
        public Image(Long imageId, String angleCode, String overlayUrl) {
            this(imageId, angleCode, overlayUrl, overlayUrl, overlayUrl != null, List.of());
        }
    }

    /**
     * 사진 위의 파손 위치 (S15P21A307-547 · -560). <b>리포트 미리보기와 PDF 가 이 한 타입을 같이
     * 쓴다</b> — 두 곳이 따로 정의하면 한쪽만 고쳐져 박스가 서로 다른 곳을 가리키게 된다.
     *
     * <p><b>사진 전체에 대한 퍼센트다.</b> 사진을 얼마로 줄이든 손상 위에 남는다. 다만 사진을
     * <b>잘라서(crop) 보여 주면 어긋난다</b> — 잘린 만큼 원점이 옮겨지기 때문이다. 이 값을 쓰는
     * 화면은 사진을 원래 비율 그대로 보여 줘야 한다.
     *
     * @param number 예상 수리비 표의 순번과 같은 번호. 표에 없는 부위가 검출됐으면 {@code null}
     *               이고 번호 없이 박스만 그린다 — 표에서 찾을 수 없는 번호를 붙이지 않는다
     */
    public record Box(Integer number, double left, double top, double width, double height) {

        /** PDF 템플릿이 번호 딱지를 그릴지 가른다. 게터 이름이 아니라 JSON 에는 나가지 않는다. */
        public boolean hasNumber() {
            return number != null;
        }
    }
}
