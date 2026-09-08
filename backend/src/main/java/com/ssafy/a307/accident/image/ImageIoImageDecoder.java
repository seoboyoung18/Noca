package com.ssafy.a307.accident.image;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * JDK 내장 {@code ImageIO} 만으로 동작하는 기본 디코더. 새 의존성이 없다.
 * <p>
 * JPEG·PNG 는 표준 플러그인이 처리한다. <b>HEIC 는 지원하지 않는다</b> — JDK 에 HEIF 플러그인이
 * 없고 순수 자바 라이브러리도 없다. 그래서 업로드 URL 발급 단계에서 미리 거절하고
 * 클라이언트 변환을 안내한다(answer25 D3). 여기서 조용히 실패시키지 않는 이유는,
 * 이미 S3 에 올라간 뒤 처리 단계에서 깨지면 사용자가 이유를 알 수 없기 때문이다.
 */
@Component
public class ImageIoImageDecoder implements ImageDecoderPort {

    @Override
    public boolean supports(ImageFormat format) {
        return format == ImageFormat.JPEG || format == ImageFormat.PNG;
    }

    @Override
    public BufferedImage decode(byte[] content, ImageFormat format) {
        if (!supports(format)) {
            throw new AccidentImageValidationException(
                    AccidentImageValidationException.Reason.SERVER_CONVERSION_UNSUPPORTED,
                    format.name() + " 형식은 서버 변환을 지원하지 않습니다. 업로드 전 JPG 로 변환해 주세요.");
        }
        if (content == null || content.length == 0) {
            throw new AccidentImageValidationException(
                    AccidentImageValidationException.Reason.MISSING_FILE, "이미지 파일이 필요합니다.");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new AccidentImageValidationException(
                    AccidentImageValidationException.Reason.SIGNATURE_MISMATCH,
                    "이미지를 읽을 수 없습니다.");
        }
        if (image == null) {
            throw new AccidentImageValidationException(
                    AccidentImageValidationException.Reason.SIGNATURE_MISMATCH,
                    "이미지를 읽을 수 없습니다.");
        }
        return image;
    }
}
