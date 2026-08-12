package com.bellgado.logistics_ted.storage;

/**
 * Anything the object store could not do. Carries no SDK type on purpose, so callers stay free of
 * the AWS dependency, and no credential or endpoint detail is put in the message.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Storage is switched off or misconfigured — a 503, not a bug in the request. */
    public static class Unavailable extends StorageException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
