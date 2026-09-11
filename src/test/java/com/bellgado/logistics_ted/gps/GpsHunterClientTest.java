package com.bellgado.logistics_ted.gps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Envelope shape and value mapping, with the HTTP leg replaced by a lambda. */
class GpsHunterClientTest {

    private static final String PASSWORD = "s3cr<t&pass";
    private final GpsHunterProperties props =
        new GpsHunterProperties(true, "https://gps.example/soap", "demo-user", PASSWORD, 200, 4, "Europe/Sofia", 60);

    private GpsHunterClient answering(String fixture, AtomicReference<String> sent) {
        return new GpsHunterClient(props, envelope -> {
            sent.set(envelope);
            return new SoapTransport.Response(200, GpsFixtures.read(fixture));
        });
    }

    @Test
    void theEnvelopeIsWhatGpsBgAccepts() {
        AtomicReference<String> sent = new AtomicReference<>();
        answering("routes.xml", sent).vehicleRoutes("49138",
            LocalDateTime.of(2026, 9, 10, 0, 0), LocalDateTime.of(2026, 9, 10, 23, 59, 59));

        String env = sent.get();
        assertTrue(env.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(env.contains("xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""));   // 1.1; 1.2 is refused
        assertTrue(env.contains("<soap:Envelope xmlns:soap"));
        assertTrue(env.contains("soap:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"));
        assertTrue(env.contains("<function xsi:type=\"xsd:string\">VehicleRoutesList</function>"));
        assertTrue(env.contains("enc:arrayType=\"xsd:string[3]\""));
        assertTrue(env.contains("<item xsi:type=\"xsd:string\">49138</item>"));               // the code as a string
        assertTrue(env.contains("<item xsi:type=\"xsd:string\">2026-09-10 23:59:59</item>"));
        assertTrue(env.contains("<pass xsi:type=\"xsd:string\">s3cr&lt;t&amp;pass</pass>"));   // escaped
    }

    @Test
    void statusMapsToTypedValuesInBulgarianTime() {
        List<VehicleStatus> list = answering("getstatus.xml", new AtomicReference<>()).getStatus();

        assertEquals(2, list.size());
        VehicleStatus moving = list.get(0);
        assertEquals("92195", moving.code());
        assertEquals("СВ0000АА", moving.regNumber());
        assertEquals(Instant.parse("2026-09-11T09:20:25Z"), moving.lastTs());   // 12:20:25 EEST = UTC+3
        assertTrue(moving.ignition());
        assertEquals(29.2, moving.speedKmh());
        assertEquals(151, moving.heading());
        assertEquals(93634.2, moving.odometerKm());
        assertNull(moving.driver());           // "Неизвестен" is GPS.bg's placeholder, not a name
        assertNull(moving.address());          // ""
        assertEquals("1.62", moving.raw().get("LastAcceleration"));   // typed xsd:int, isn't one

        assertFalse(list.get(1).ignition());
        assertEquals(Instant.parse("2026-09-09T11:27:34Z"), list.get(1).lastTs());
    }

    @Test
    void segmentsMapTheProviderFields() {
        List<RouteSegment> segs = answering("routes.xml", new AtomicReference<>()).vehicleRoutes("49138",
            LocalDateTime.of(2026, 9, 10, 0, 0), LocalDateTime.of(2026, 9, 10, 23, 59, 59));

        RouteSegment drive = segs.get(0);
        assertTrue(drive.moving());
        assertEquals(Instant.parse("2026-09-10T05:28:34Z"), drive.start());
        assertEquals(Instant.parse("2026-09-10T05:34:09Z"), drive.end());
        assertEquals(335, drive.durationS());
        assertEquals(1.8369743650995, drive.distanceKm());
        assertEquals(85, drive.idleS());
        assertEquals(0.14695794920796, drive.fuelEstL());
        assertNull(drive.driver());
        assertNull(drive.startPlace());

        RouteSegment zeroLengthStop = segs.get(2);
        assertFalse(zeroLengthStop.moving());
        assertEquals(zeroLengthStop.start(), zeroLengthStop.end());
        assertEquals(0, zeroLengthStop.durationS());
    }

    @Test
    void aNetworkFailureIsATransportErrorAndNeverShowsThePassword() {
        GpsHunterClient client = new GpsHunterClient(props, envelope -> {
            throw new IOException("PKIX path building failed");
        });

        GpsHunterException e = assertThrows(GpsHunterException.class, client::getStatus);

        assertEquals(GpsHunterException.TRANSPORT, e.code());
        assertTrue(e.getMessage().contains("PKIX"));
        assertFalse(e.getMessage().contains("s3cr"));
    }

    @Test
    void aFaultNeverEchoesTheEnvelope() {
        GpsHunterClient client = new GpsHunterClient(props, envelope -> new SoapTransport.Response(500, GpsFixtures.fault(1)));

        GpsHunterException e = assertThrows(GpsHunterException.class, client::getStatus);

        assertTrue(e.isCredentialProblem());
        assertFalse(e.getMessage().contains("s3cr"));
    }

    @Test
    void theConfigNeverPrintsThePassword() {
        assertFalse(props.toString().contains("s3cr"));
    }

    @Test
    void aSecondCallWhileOneIsInFlightIsRefusedRatherThanSent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GpsHunterClient client = new GpsHunterClient(props, envelope -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new SoapTransport.Response(200, GpsFixtures.read("empty.xml"));
        });
        Thread first = new Thread(client::getStatus);
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        GpsHunterException e = assertThrows(GpsHunterException.class, client::getStatus);

        assertTrue(e.getMessage().contains("in flight"));
        release.countDown();
        first.join(5000);
    }
}
