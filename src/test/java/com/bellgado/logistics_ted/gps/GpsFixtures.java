package com.bellgado.logistics_ted.gps;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Recorded GPS.bg responses under {@code src/test/resources/gps}. See each file's header comment. */
final class GpsFixtures {

    private GpsFixtures() {
    }

    static String read(String name) {
        try (InputStream in = GpsFixtures.class.getResourceAsStream("/gps/" + name)) {
            if (in == null) throw new IllegalArgumentException("No fixture /gps/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The real fault shape (captured with an invalid function name) carrying another code. */
    static String fault(int code) {
        return read("fault-invalid-function.xml").replace("<faultcode>6</faultcode>", "<faultcode>" + code + "</faultcode>");
    }
}
