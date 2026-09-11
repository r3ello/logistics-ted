package com.bellgado.logistics_ted.gps;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * GPS.bg's GPSHunter API: one SOAP operation, {@code HunterSoapService(user, pass, function, data_arr)},
 * where {@code function} picks the real call. Only {@code GetStatus} and {@code VehicleRoutesList} are
 * confirmed for this account.
 *
 * <p>The WSDL is rpc/encoded SOAP 1.1, which generated JAX-WS/CXF clients handle badly, so the
 * envelope is written by hand exactly as GPS.bg's PHP sample sends it. Verified live:
 * SOAP 1.1 (1.2 is rejected), {@code encodingStyle} on the Envelope, every {@code data_arr} item typed
 * {@code xsd:string} — vehicle codes and dates included.
 *
 * <p>{@code @Component}, never {@code @Service}: the envelope carries the password, and nothing in this
 * package may come under {@code ServiceLoggingAspect}'s argument logging. Only {@link GpsPoller} calls
 * this; never call it from a request thread (one call per 181 s per account).
 */
@Component
@ConditionalOnProperty(prefix = "gps", name = "enabled", havingValue = "true")
public class GpsHunterClient {

    private final GpsHunterProperties props;
    private final SoapTransport transport;
    /** One call at a time, ever: a concurrent second call would trip fault 11 and restart the timer. */
    private final ReentrantLock inFlight = new ReentrantLock();

    @Autowired
    public GpsHunterClient(GpsHunterProperties props) {
        this(props, new HttpSoapTransport(props.endpoint(), Duration.ofSeconds(props.requestTimeoutSeconds())));
    }

    GpsHunterClient(GpsHunterProperties props, SoapTransport transport) {
        this.props = props;
        this.transport = transport;
    }

    /** GetStatus("0"): the current state of every vehicle on the account, in one call. */
    public List<VehicleStatus> getStatus() {
        return call("GetStatus", List.of("0")).stream()
            .map(r -> VehicleStatus.from(r, props.zone()))
            .toList();
    }

    /**
     * VehicleRoutesList: driving and stopped segments of one vehicle between two provider-local times.
     * A range without data is an empty list, not a fault.
     */
    public List<RouteSegment> vehicleRoutes(String vehicleCode, LocalDateTime from, LocalDateTime to) {
        List<String> params = List.of(vehicleCode,
            GpsValues.PROVIDER_TS.format(from), GpsValues.PROVIDER_TS.format(to));
        return call("VehicleRoutesList", params).stream()
            .map(r -> RouteSegment.from(r, props.zone()))
            .toList();
    }

    List<Map<String, String>> call(String function, List<String> params) {
        if (!inFlight.tryLock()) {
            throw new GpsHunterException(GpsHunterException.TRANSPORT, "another GPS.bg call is already in flight");
        }
        try {
            SoapTransport.Response response;
            try {
                response = transport.post(envelope(props.user(), props.password(), function, params));
            } catch (IOException e) {
                throw new GpsHunterException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GpsHunterException("interrupted", e);
            }
            return GpsHunterResponseParser.parse(response.status(), response.body());
        } finally {
            inFlight.unlock();
        }
    }

    /** The request body. Contains the password: never log it. */
    static String envelope(String user, String password, String function, List<String> params) {
        StringBuilder items = new StringBuilder();
        for (String p : params) {
            items.append("<item xsi:type=\"xsd:string\">").append(escape(p)).append("</item>");
        }
        String arrayType = params.isEmpty() ? "xsd:anyType[0]" : "xsd:string[" + params.size() + "]";
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xmlns:xsd="http://www.w3.org/2001/XMLSchema" \
            xmlns:enc="http://schemas.xmlsoap.org/soap/encoding/" \
            soap:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\
            <soap:Body><ns1:HunterSoapService xmlns:ns1="urn:GPSHunter">\
            <user xsi:type="xsd:string">%s</user>\
            <pass xsi:type="xsd:string">%s</pass>\
            <function xsi:type="xsd:string">%s</function>\
            <data_arr xsi:type="enc:Array" enc:arrayType="%s">%s</data_arr>\
            </ns1:HunterSoapService></soap:Body></soap:Envelope>"""
            .formatted(escape(user), escape(password), escape(function), arrayType, items);
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
