package com.bellgado.logistics_ted.web.importer.csv;

/**
 * The dialect of one uploaded file. Both settings are explicit request parameters and are never
 * auto-detected: {@code 1,234} is genuinely ambiguous between 1234 and 1.234, and guessing wrong
 * silently corrupts every quantity and price in the file.
 *
 * @param delimiter    field separator — {@code ,} by default, {@code ;} for Bulgarian-locale
 *                     spreadsheet exports
 * @param decimalComma whether {@code 42,6977} means 42.6977
 */
public record CsvFormat(char delimiter, boolean decimalComma) {

    public static CsvFormat defaults() {
        return new CsvFormat(',', false);
    }

    /**
     * Builds a format from the request parameters, falling back to the defaults for null/blank.
     *
     * @param delimiter {@code comma} | {@code semicolon} | {@code tab}
     * @param decimal   {@code dot} | {@code comma}
     */
    public static CsvFormat of(String delimiter, String decimal) {
        char d = switch (delimiter == null ? "comma" : delimiter.trim().toLowerCase()) {
            case "semicolon", ";" -> ';';
            case "tab", "\\t"     -> '\t';
            case "", "comma", "," -> ',';
            default -> throw new CsvException(
                com.bellgado.logistics_ted.service.importer.ImportErrorCode.MALFORMED_CSV,
                "Unsupported delimiter '" + delimiter + "'. Use comma, semicolon or tab.");
        };
        boolean comma = decimal != null && decimal.trim().equalsIgnoreCase("comma");
        return new CsvFormat(d, comma);
    }
}
