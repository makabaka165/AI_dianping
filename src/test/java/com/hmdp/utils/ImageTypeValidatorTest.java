package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageTypeValidatorTest {

    @Test
    void legalJpegPngAndWebpHeadersShouldPass() {
        ImageTypeValidator.validateMagicBytes(file("a.jpg", bytes(0xFF, 0xD8, 0xFF, 0x00)), "jpg");
        ImageTypeValidator.validateMagicBytes(file("a.png",
                bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)), "png");
        ImageTypeValidator.validateMagicBytes(file("a.webp",
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}), "webp");
    }

    @Test
    void jpgSuffixWithTextContentShouldBeRejected() {
        assertThatThrownBy(() -> ImageTypeValidator.validateMagicBytes(
                file("a.jpg", "hello".getBytes()), "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pngSuffixWithJpegContentShouldBeRejected() {
        assertThatThrownBy(() -> ImageTypeValidator.validateMagicBytes(
                file("a.png", bytes(0xFF, 0xD8, 0xFF, 0x00)), "png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOrTooSmallHeaderShouldBeRejected() {
        assertThatThrownBy(() -> ImageTypeValidator.validateMagicBytes(file("a.jpg", new byte[0]), "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageTypeValidator.validateMagicBytes(file("a.webp", bytes('R', 'I')), "webp"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
