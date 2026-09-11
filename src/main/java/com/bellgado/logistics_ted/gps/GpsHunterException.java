package com.bellgado.logistics_ted.gps;

/**
 * A GPSHunter call that returned no data. {@link #code()} is the provider's fault number (GPS.bg sends
 * {@code <faultcode>N</faultcode>} with HTTP 500 and a useless {@code faultstring} of "Error !!!"), or
 * {@link #TRANSPORT} when no SOAP answer arrived: network/TLS failure, timeout, or a body that is not
 * the expected XML.
 *
 * <p>Messages never contain the request envelope — it carries the account password.
 */
public class GpsHunterException extends RuntimeException {

    public static final int TRANSPORT = -1;
    public static final int INVALID_CREDENTIALS = 1;
    public static final int NO_ACCESS = 5;
    /** "Database query in less than the allowed period! MIN is 181 sec." */
    public static final int THROTTLED = 11;

    private final int code;

    public GpsHunterException(int code, String detail) {
        super(message(code, detail));
        this.code = code;
    }

    public GpsHunterException(String detail, Throwable cause) {
        super(message(TRANSPORT, detail), cause);
        this.code = TRANSPORT;
    }

    public int code() {
        return code;
    }

    public boolean isThrottled() {
        return code == THROTTLED;
    }

    public boolean isCredentialProblem() {
        return code == INVALID_CREDENTIALS || code == NO_ACCESS;
    }

    /** The request itself was fine; the same call can succeed later. */
    public boolean isTransient() {
        return code == THROTTLED || code == TRANSPORT;
    }

    /** GPS.bg's error_codes.txt. Their faultstring never says any of this. */
    static String describe(int code) {
        return switch (code) {
            case TRANSPORT -> "no SOAP response";
            case 0 -> "ok";
            case 1 -> "invalid user name or password";
            case 2 -> "missing function name";
            case 3 -> "missing input parameters";
            case 4 -> "requested object does not belong to this account";
            case 5 -> "account cannot access the system";
            case 6 -> "invalid function name";
            case 8 -> "invalid object code";
            case 9 -> "requested period is too long";
            case 10 -> "end of the period is before its start";
            case 11 -> "called again within 181 seconds";
            default -> "undocumented code";
        };
    }

    private static String message(int code, String detail) {
        String head = code == TRANSPORT
            ? "GPS.bg call failed"
            : "GPS.bg fault " + code + " (" + describe(code) + ")";
        return detail == null || detail.isBlank() ? head : head + ": " + detail;
    }
}
