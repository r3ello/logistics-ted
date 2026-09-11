package com.bellgado.logistics_ted.gps;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Plain JDK {@link HttpClient}: the envelope is built by hand (see {@link GpsHunterClient}), so no SOAP
 * stack is needed — JAX-WS/CXF mis-handle this rpc/encoded WSDL anyway.
 *
 * <p>TLS uses the JVM's default trust store; verification is never disabled. If a host cannot build
 * GPS.bg's chain, fix the trust store (e.g. {@code JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=...}).
 */
final class HttpSoapTransport implements SoapTransport {

    static final String SOAP_ACTION = "\"urn:GPSHunterAction\"";
    /** What GPS.bg's own PHP sample client sends. */
    static final String USER_AGENT = "GPSSoap/2.7.3 (APIClient)";

    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;

    HttpSoapTransport(String endpoint, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
            // A legacy PHP SOAP endpoint: skip HTTP/2 negotiation entirely.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    @Override
    public Response post(String envelope) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "text/xml; charset=UTF-8")
            .header("SOAPAction", SOAP_ACTION)
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }
}
