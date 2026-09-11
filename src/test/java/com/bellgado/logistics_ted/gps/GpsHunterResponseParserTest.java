package com.bellgado.logistics_ted.gps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The parser against recorded GPS.bg responses — the format is only as documented as these files. */
class GpsHunterResponseParserTest {

    @Test
    void statusRowsKeepEveryFieldWithoutTheLiteralQuotes() {
        List<Map<String, String>> rows = GpsHunterResponseParser.parse(200, GpsFixtures.read("getstatus.xml"));

        assertEquals(2, rows.size());
        Map<String, String> first = rows.get(0);
        assertEquals(46, first.size());
        assertEquals("92195", first.get("Code"));
        assertEquals("МАСТЪР БОБИ", first.get("Name"));          // Cyrillic intact, quotes gone
        assertEquals("2026-09-11 12:20:25", first.get("LastTS"));
        assertEquals("", first.get("Address"));                  // "" is an empty value, not two quotes
        assertEquals("Неизвестен", first.get("LastDriver"));     // never quoted in the first place
        assertTrue(first.containsKey("AnamaxVal1"));             // GPS.bg's casing, not the doc's AnaMaxVal1
    }

    @Test
    void routeRowsParseTheSameWay() {
        List<Map<String, String>> rows = GpsHunterResponseParser.parse(200, GpsFixtures.read("routes.xml"));

        assertEquals(3, rows.size());
        assertEquals("1", rows.get(0).get("Ign"));
        assertEquals("25%", rows.get(0).get("Idle"));
        assertEquals("1.8369743650995", rows.get(0).get("Distance"));
        assertEquals("", rows.get(0).get("sPlace"));
    }

    @Test
    void anEmptyRangeIsAnEmptyListNotAnError() {
        assertTrue(GpsHunterResponseParser.parse(200, GpsFixtures.read("empty.xml")).isEmpty());
    }

    @Test
    void aFaultBecomesAnExceptionWithTheProviderCodeExplained() {
        GpsHunterException e = assertThrows(GpsHunterException.class,
            () -> GpsHunterResponseParser.parse(500, GpsFixtures.read("fault-invalid-function.xml")));

        assertEquals(6, e.code());
        assertFalse(e.isTransient());
        assertTrue(e.getMessage().contains("invalid function name"), e.getMessage());
    }

    @Test
    void theThrottleFaultIsRecognised() {
        GpsHunterException e = assertThrows(GpsHunterException.class,
            () -> GpsHunterResponseParser.parse(500, GpsFixtures.fault(11)));

        assertTrue(e.isThrottled());
        assertTrue(e.isTransient());
    }

    @Test
    void aBodyThatIsNotASoapAnswerIsATransportError() {
        GpsHunterException html = assertThrows(GpsHunterException.class,
            () -> GpsHunterResponseParser.parse(502, "<html><body>Bad gateway</body></html>"));
        GpsHunterException text = assertThrows(GpsHunterException.class,
            () -> GpsHunterResponseParser.parse(503, "Service Unavailable"));
        GpsHunterException empty = assertThrows(GpsHunterException.class,
            () -> GpsHunterResponseParser.parse(200, ""));

        assertEquals(GpsHunterException.TRANSPORT, html.code());
        assertEquals(GpsHunterException.TRANSPORT, text.code());
        assertEquals(GpsHunterException.TRANSPORT, empty.code());
        assertTrue(html.getMessage().contains("HTTP 502"));
    }

    @Test
    void externalEntitiesAreNeverResolved() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///no/such/file\">]>"
            + "<x><HunterSoapServiceReturn><item><Code>&e;</Code></item></HunterSoapServiceReturn></x>";

        GpsHunterException e = assertThrows(GpsHunterException.class, () -> GpsHunterResponseParser.parse(200, xxe));

        assertEquals(GpsHunterException.TRANSPORT, e.code());
    }
}
