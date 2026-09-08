package com.ssafy.a307.accident.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXIF Orientation 1~8 을 모두 확인한다. 방향 보정은 눈으로 보면 맞는 것 같아도 5·7(전치·역전치)이
 * 조용히 뒤집혀 있는 일이 흔하므로, 픽셀 좌표 대응을 값으로 단언한다.
 */
@DisplayName("EXIF 방향 보정")
class ExifOrientationTest {

    private static final int WIDTH = 4;
    private static final int HEIGHT = 2;

    @ParameterizedTest(name = "orientation {0} 을 읽는다")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("JPEG APP1 세그먼트에서 orientation 을 읽는다")
    void readsOrientation(int orientation) {
        byte[] jpeg = TestImages.jpegWithOrientation(WIDTH, HEIGHT, orientation);

        assertThat(ExifOrientation.readJpegOrientation(jpeg)).isEqualTo(orientation);
    }

    @Test
    @DisplayName("EXIF 가 없는 JPEG 은 1로 본다")
    void withoutExif() {
        assertThat(ExifOrientation.readJpegOrientation(TestImages.jpeg(WIDTH, HEIGHT)))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("JPEG 이 아니거나 손상된 바이트에서도 1을 돌려주고 예외를 던지지 않는다")
    void malformedInput() {
        assertThat(ExifOrientation.readJpegOrientation(TestImages.png(2, 2)))
                .isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.readJpegOrientation(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
                .isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.readJpegOrientation(new byte[0]))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("범위를 벗어난 orientation 값은 1로 본다")
    void outOfRangeValue() {
        assertThat(ExifOrientation.readJpegOrientation(
                TestImages.jpegWithOrientation(WIDTH, HEIGHT, 9)))
                .isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.readJpegOrientation(
                TestImages.jpegWithOrientation(WIDTH, HEIGHT, 0)))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("orientation 1 은 원본을 그대로 돌려준다")
    void normalIsIdentity() {
        BufferedImage source = TestImages.distinctPixels(WIDTH, HEIGHT);

        assertThat(ExifOrientation.apply(source, 1)).isSameAs(source);
    }

    @ParameterizedTest(name = "orientation {0} 은 가로세로가 그대로다")
    @ValueSource(ints = {2, 3, 4})
    @DisplayName("2·3·4 는 크기가 바뀌지 않는다")
    void keepsDimensions(int orientation) {
        BufferedImage result = ExifOrientation.apply(TestImages.distinctPixels(WIDTH, HEIGHT), orientation);

        assertThat(result.getWidth()).isEqualTo(WIDTH);
        assertThat(result.getHeight()).isEqualTo(HEIGHT);
    }

    @ParameterizedTest(name = "orientation {0} 은 가로세로가 바뀐다")
    @ValueSource(ints = {5, 6, 7, 8})
    @DisplayName("5~8 은 가로세로가 뒤바뀐다")
    void swapsDimensions(int orientation) {
        BufferedImage result = ExifOrientation.apply(TestImages.distinctPixels(WIDTH, HEIGHT), orientation);

        assertThat(result.getWidth()).isEqualTo(HEIGHT);
        assertThat(result.getHeight()).isEqualTo(WIDTH);
    }

    @ParameterizedTest(name = "orientation {0} 의 픽셀 대응이 맞다")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("모든 픽셀이 EXIF 규격대로 옮겨진다")
    void movesEveryPixel(int orientation) {
        BufferedImage source = TestImages.distinctPixels(WIDTH, HEIGHT);
        BufferedImage result = ExifOrientation.apply(source, orientation);

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int[] target = expected(orientation, x, y);
                assertThat(result.getRGB(target[0], target[1]))
                        .as("orientation %d: (%d,%d) → (%d,%d)", orientation, x, y, target[0], target[1])
                        .isEqualTo(TestImages.color(x, y));
            }
        }
    }

    /**
     * EXIF 규격의 좌표 대응. {@code w}·{@code h} 는 원본 크기다.
     * <pre>
     * 1 (x,y)              2 (w-1-x, y)        3 (w-1-x, h-1-y)    4 (x, h-1-y)
     * 5 (y, x)             6 (h-1-y, x)        7 (h-1-y, w-1-x)    8 (y, w-1-x)
     * </pre>
     */
    private static int[] expected(int orientation, int x, int y) {
        return switch (orientation) {
            case 1 -> new int[]{x, y};
            case 2 -> new int[]{WIDTH - 1 - x, y};
            case 3 -> new int[]{WIDTH - 1 - x, HEIGHT - 1 - y};
            case 4 -> new int[]{x, HEIGHT - 1 - y};
            case 5 -> new int[]{y, x};
            case 6 -> new int[]{HEIGHT - 1 - y, x};
            case 7 -> new int[]{HEIGHT - 1 - y, WIDTH - 1 - x};
            case 8 -> new int[]{y, WIDTH - 1 - x};
            default -> throw new IllegalArgumentException("orientation " + orientation);
        };
    }
}
