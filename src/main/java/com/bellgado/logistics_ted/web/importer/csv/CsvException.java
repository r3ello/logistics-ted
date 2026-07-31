package com.bellgado.logistics_ted.web.importer.csv;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;

/**
 * A file-level failure: the upload could not be read at all, so there is no report to return —
 * only a {@code 400} with the reason. Row-level problems use {@link CsvValueException} instead and
 * are collected into the report.
 */
public class CsvException extends RuntimeException {

    private final ImportErrorCode code;

    public CsvException(ImportErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CsvException(ImportErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ImportErrorCode getCode() {
        return code;
    }
}
