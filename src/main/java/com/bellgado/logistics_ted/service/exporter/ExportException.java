package com.bellgado.logistics_ted.service.exporter;

/** A request the export cannot serve as asked — always the caller's fault, always a 400. */
public class ExportException extends RuntimeException {

    public ExportException(String message) {
        super(message);
    }
}
