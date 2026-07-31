package com.bellgado.logistics_ted.web.importer.csv;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;

/**
 * A single cell could not be read as the type the importer asked for. Carries enough context for
 * the report to be actionable by whoever maintains the spreadsheet — line, column, the offending
 * value and a message that says what to do about it.
 */
public class CsvValueException extends RuntimeException {

    private final int line;
    private final String column;
    private final String value;
    private final ImportErrorCode code;

    public CsvValueException(int line, String column, String value, ImportErrorCode code, String message) {
        super(message);
        this.line = line;
        this.column = column;
        this.value = value;
        this.code = code;
    }

    public int getLine()               { return line; }
    public String getColumn()          { return column; }
    public String getValue()           { return value; }
    public ImportErrorCode getCode()   { return code; }
}
