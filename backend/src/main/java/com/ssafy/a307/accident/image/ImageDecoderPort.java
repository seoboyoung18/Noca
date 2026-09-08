package com.ssafy.a307.accident.image;

import java.awt.image.BufferedImage;

/**
 * 이미지 바이트를 픽셀로 여는 경계.
 * <p>
 * <b>HEIC 판정(answer25 D3)이 이 인터페이스로 표현된다.</b> Java {@code ImageIO} 는 HEIC 를
 * 디코드하지 못하고 새 의존성 추가는 금지되어 있으므로, 기본 구현
 * {@link ImageIoImageDecoder} 는 HEIC 에 대해 {@link #supports(ImageFormat)} 가 false 다.
 * 서버 변환으로 방침이 바뀌면(libheif/ImageMagick) 이 포트의 다른 구현을 넣으면 되고
 * 검증·전처리·서비스 코드는 손대지 않는다.
 * <p>
 * 형식 지원 여부를 {@code ImageFormat} 이 아니라 디코더가 답하게 둔 이유가 그것이다 —
 * "요구사항이 받아들이는 형식"과 "이 배포본이 열 수 있는 형식"은 다른 사실이다.
 */
public interface ImageDecoderPort {

    /** 이 배포본이 해당 형식을 실제로 디코딩할 수 있는가. */
    boolean supports(ImageFormat format);

    /**
     * @throws AccidentImageValidationException 지원하지 않는 형식이거나 바이트가 손상된 경우
     */
    BufferedImage decode(byte[] content, ImageFormat format);
}
