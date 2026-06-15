package com.hmdp.utils;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class ImageTypeValidator {

    private static final int MAX_HEADER_LENGTH = 12;

    private ImageTypeValidator() {
    }

    public static void validateMagicBytes(MultipartFile image, String suffix) {
        byte[] header = new byte[MAX_HEADER_LENGTH];
        int length;
        try (InputStream inputStream = image.getInputStream()) {
            length = readHeader(inputStream, header);
        } catch (IOException e) {
            throw new RuntimeException("read image header failed", e);
        }
        ImageType imageType = detect(header, length);
        if (imageType == null || !matchesSuffix(imageType, suffix)) {
            throw new IllegalArgumentException("image content type does not match filename suffix");
        }
    }

    static ImageType detect(byte[] header, int length) {
        if (length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return ImageType.JPEG;
        }
        if (length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && header[4] == 0x0D
                && header[5] == 0x0A
                && header[6] == 0x1A
                && header[7] == 0x0A) {
            return ImageType.PNG;
        }
        if (length >= 12
                && header[0] == 0x52
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x46
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50) {
            return ImageType.WEBP;
        }
        return null;
    }

    private static boolean matchesSuffix(ImageType imageType, String suffix) {
        String normalizedSuffix = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        if (imageType == ImageType.JPEG) {
            return "jpg".equals(normalizedSuffix) || "jpeg".equals(normalizedSuffix);
        }
        if (imageType == ImageType.PNG) {
            return "png".equals(normalizedSuffix);
        }
        return imageType == ImageType.WEBP && "webp".equals(normalizedSuffix);
    }

    private static int readHeader(InputStream inputStream, byte[] header) throws IOException {
        int offset = 0;
        while (offset < header.length) {
            int read = inputStream.read(header, offset, header.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    enum ImageType {
        JPEG,
        PNG,
        WEBP
    }
}
