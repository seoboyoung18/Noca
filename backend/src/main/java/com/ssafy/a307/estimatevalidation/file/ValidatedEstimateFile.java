package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;

public record ValidatedEstimateFile(
        String originalFilename,
        String extension,
        String contentType,
        EstimateFileType fileType,
        long size,
        byte[] content) {

    public ValidatedEstimateFile {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
