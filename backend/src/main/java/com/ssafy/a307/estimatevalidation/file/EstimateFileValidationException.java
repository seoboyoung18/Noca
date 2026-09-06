package com.ssafy.a307.estimatevalidation.file;

public final class EstimateFileValidationException extends IllegalArgumentException {

    private final Reason reason;

    public EstimateFileValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING_FILE,
        FILE_TOO_LARGE,
        INVALID_FILE_NAME,
        UNSUPPORTED_EXTENSION,
        UNSUPPORTED_CONTENT_TYPE,
        SIGNATURE_MISMATCH
    }
}
