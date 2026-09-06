package com.ssafy.a307.estimatevalidation.file;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public interface DocumentStoragePort {

    StoredDocument store(StoreDocument request);

    void delete(String storageKey);

    default void deleteAll(List<String> storageKeys) {
        storageKeys.forEach(this::delete);
    }

    URI createPresignedDownloadUrl(String storageKey, Duration validity);

    record StoreDocument(byte[] content, String contentType, String extension) {
        public StoreDocument {
            content = content == null ? null : content.clone();
            Objects.requireNonNull(content, "content");
            if (content.length == 0) throw new IllegalArgumentException("content must not be empty");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
            if (extension == null || extension.isBlank()) {
                throw new IllegalArgumentException("extension is required");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record StoredDocument(String storageKey, long size, String contentType) {
        public StoredDocument {
            if (storageKey == null || storageKey.isBlank()) {
                throw new IllegalArgumentException("storageKey is required");
            }
            if (size < 1) throw new IllegalArgumentException("size must be positive");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
        }
    }
}
