package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.entity.ImageVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 이미지 전처리(Task 138) — EXIF 방향 보정 후 분석용·썸네일 리사이즈본을 만든다.
 * 새 의존성 없이 {@code ImageIO} 와 {@code java.awt} 만 쓴다.
 *
 * <p>규격은 요구사항에 수치가 없어 {@link AccidentImageProperties} 로 뺐다. 규칙은 네 가지다.
 * <ul>
 *   <li><b>종횡비 유지, 긴 변 기준 축소.</b> 확대하지 않는다 — 원본보다 작은 이미지의 리사이즈본은
 *       원본과 같은 크기가 된다. 없는 정보를 늘려 만들면 분석이 더 나아지지 않는다</li>
 *   <li><b>EXIF orientation 1~8 을 모두 처리</b>하고, 보정 후에는 회전 지시를 남기지 않는다.
 *       남기면 뷰어가 두 번 돌린다</li>
 *   <li><b>전처리본에서 EXIF 를 통째로 제거한다.</b> JPEG 재인코딩 경로가 APP1 을 쓰지 않으므로
 *       GPS·기기 정보가 자동으로 사라진다. 사고 이미지에 촬영 위치가 남는 것은 개인정보 문제다</li>
 *   <li><b>원본은 손대지 않는다.</b> 정본 DDL 이 {@code ORIGINAL} 을 별도 variant 로 두는 이유다 —
 *       원본은 그대로 보관하고 노출용·분석용만 가공한다</li>
 * </ul>
 *
 * <p>전처리본은 원본 형식과 무관하게 <b>JPEG</b> 로 인코딩한다. 변형본은 사람이 보고 모델이 읽는
 * 용도라 무손실이 필요 없고, PNG 로 두면 사진에서 용량이 몇 배로 커진다. 투명 PNG 가 들어오면
 * 흰 배경에 합성한다 — 알파를 그냥 버리면 투명 영역이 검게 나온다.
 */
@Component
@RequiredArgsConstructor
public class AccidentImagePreprocessor {

    private final ImageDecoderPort decoder;
    private final AccidentImageProperties properties;

    /**
     * @param original 저장소에서 읽은 원본 바이트
     * @param format   시그니처까지 검증된 형식
     */
    public Preprocessed preprocess(byte[] original, ImageFormat format) {
        BufferedImage decoded = decoder.decode(original, format);
        int orientation = format == ImageFormat.JPEG
                ? ExifOrientation.readJpegOrientation(original)
                : ExifOrientation.NORMAL;
        BufferedImage oriented = ExifOrientation.apply(decoded, orientation);

        return new Preprocessed(
                oriented.getWidth(),
                oriented.getHeight(),
                orientation,
                render(oriented, ImageVariant.RESIZED, properties.resizedMaxEdgePx()),
                render(oriented, ImageVariant.THUMBNAIL, properties.thumbnailMaxEdgePx()));
    }

    private RenderedVariant render(BufferedImage oriented, ImageVariant variant, int maxEdgePx) {
        BufferedImage scaled = scale(oriented, maxEdgePx);
        byte[] jpeg = encodeJpeg(scaled);
        return new RenderedVariant(variant, jpeg, scaled.getWidth(), scaled.getHeight());
    }

    /** 긴 변이 {@code maxEdgePx} 를 넘지 않도록 축소. 이미 작으면 그대로 둔다(확대 금지). */
    private BufferedImage scale(BufferedImage source, int maxEdgePx) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= maxEdgePx) return source;

        double ratio = (double) maxEdgePx / longEdge;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** JPEG 로 인코딩한다. {@code ImageIO} 의 JPEG 라이터는 EXIF 를 쓰지 않으므로 메타가 사라진다. */
    private byte[] encodeJpeg(BufferedImage image) {
        BufferedImage opaque = toOpaque(image);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(opaque, "jpg", out)) {
                throw new IllegalStateException("JPEG writer not available");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("이미지 인코딩에 실패했습니다.", e);
        }
        return out.toByteArray();
    }

    /** 알파가 있으면 흰 배경에 합성한다. JPEG 는 알파를 담지 못한다. */
    private BufferedImage toOpaque(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        BufferedImage target = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * @param width            EXIF 보정 후의 표시 크기. 원본 파일이 디코드되는 크기와 다를 수 있다
     *                         (orientation 6·8 은 가로세로가 바뀐다)
     * @param appliedOrientation 실제로 적용한 orientation 값. 1이면 보정할 것이 없었다는 뜻이다
     */
    public record Preprocessed(
            int width,
            int height,
            int appliedOrientation,
            RenderedVariant resized,
            RenderedVariant thumbnail) {

        /** 품질 판정이 보는 짧은 변. 회전 보정 후 기준이다. */
        public int shortEdge() {
            return Math.min(width, height);
        }
    }

    public record RenderedVariant(ImageVariant variant, byte[] content, int width, int height) {
        public RenderedVariant {
            content = content == null ? null : content.clone();
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public String contentType() {
            return ImageFormat.JPEG.contentType();
        }

        public String extension() {
            return ImageFormat.JPEG.canonicalExtension();
        }
    }
}
