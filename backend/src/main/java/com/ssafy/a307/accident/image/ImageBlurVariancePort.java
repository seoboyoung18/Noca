package com.ssafy.a307.accident.image;

/**
 * 라플라시안 분산(흔들림·초점 불량) 계산 경계. <b>구현체는 이 작업에 없다.</b>
 * <p>
 * 블러 판정 모듈은 {@code S15P21A307-120 [AI] 이미지 품질 검사(해상도·블러) 모듈 구현} 으로
 * AI 담당 범위다. 여기서 대신 구현하면 두 곳에 같은 판정이 생기고, 임계값
 * {@code app.image-quality.blur-variance-threshold} 의 주인이 모호해진다.
 * <p>
 * 그래서 경계만 만들어 둔다. {@link ImageQualityAssessor} 는 이 포트를
 * {@code Optional} 로 주입받아 <b>없으면 블러 판정을 건너뛴다</b> — 가짜 값으로 판정하지 않는다.
 * AI 모듈이 붙으면 이 인터페이스를 구현한 빈을 등록하면 되고 나머지 코드는 그대로다.
 */
public interface ImageBlurVariancePort {

    /**
     * @param content 판정할 이미지 바이트(분석용 리사이즈본)
     * @return 라플라시안 분산. 낮을수록 흐리다
     */
    double variance(byte[] content);
}
