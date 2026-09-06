package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Component
public class EstimateFileValidator {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "pdf", "application/pdf");

    public ValidatedEstimateFile validate(MultipartFile file) {
        if (file == null) throw invalid("견적서 파일이 필요합니다.");
        if (file.isEmpty()) throw invalid("견적서 파일이 필요합니다.");
        if (file.getSize() > MAX_FILE_SIZE) throw invalid("견적서 파일은 10MB 이하여야 합니다.");
        try {
            return validate(file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getBytes());
        } catch (EstimateFileValidationException e) {
            throw invalid(e.getMessage());
        } catch (IOException e) {
            throw invalid("견적서 파일을 읽을 수 없습니다.");
        }
    }

    public ValidatedEstimateFile validate(String originalName, String contentType, byte[] content) {
        return validate(originalName, contentType, content == null ? 0 : content.length, content);
    }

    public ValidatedEstimateFile validate(
            String originalName, String contentType, long declaredSize, byte[] content) {
        if (content == null || content.length == 0 || declaredSize < 1) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.MISSING_FILE, "견적서 파일이 필요합니다.");
        }
        if (declaredSize > MAX_FILE_SIZE) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.FILE_TOO_LARGE, "견적서 파일은 10MB 이하여야 합니다.");
        }
        validateFilename(originalName);
        String extension = extension(originalName);
        String expectedContentType = CONTENT_TYPES.get(extension);
        if (expectedContentType == null) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.UNSUPPORTED_EXTENSION,
                    "JPG, PNG, PDF 파일만 등록할 수 있습니다.");
        }
        String normalizedContentType = normalizeContentType(contentType);
        if (!expectedContentType.equals(normalizedContentType)) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.UNSUPPORTED_CONTENT_TYPE,
                    "파일 Content-Type과 확장자가 일치하지 않습니다.");
        }
        EstimateFileType detected = detect(content);
        EstimateFileType expected = extension.equals("pdf") ? EstimateFileType.PDF : EstimateFileType.IMAGE;
        if (detected != expected || (extension.equals("png") && !isPng(content))
                || ((extension.equals("jpg") || extension.equals("jpeg")) && !isJpeg(content))) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.SIGNATURE_MISMATCH,
                    "파일 시그니처와 확장자가 일치하지 않습니다.");
        }
        return new ValidatedEstimateFile(
                originalName, extension, normalizedContentType, detected, declaredSize, content);
    }

    private EstimateFileType detect(byte[] bytes) {
        if (isJpeg(bytes) || isPng(bytes)) return EstimateFileType.IMAGE;
        if (isPdf(bytes)) return EstimateFileType.PDF;
        throw new EstimateFileValidationException(
                EstimateFileValidationException.Reason.SIGNATURE_MISMATCH,
                "지원하는 파일 시그니처가 아닙니다.");
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
    }

    private boolean isPng(byte[] bytes) {
        int[] signature = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if ((bytes[i] & 0xff) != signature[i]) return false;
        return true;
    }

    private boolean isPdf(byte[] bytes) {
        byte[] signature = {'%', 'P', 'D', 'F', '-'};
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return true;
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.INVALID_FILE_NAME,
                    "원본 파일명이 올바르지 않습니다.");
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new EstimateFileValidationException(
                    EstimateFileValidationException.Reason.UNSUPPORTED_EXTENSION,
                    "파일 확장자가 필요합니다.");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
