package com.bellgado.logistics_ted.gps;

import java.io.IOException;

/** The HTTP leg of a GPSHunter call, split out so the client can be tested without a network. */
@FunctionalInterface
interface SoapTransport {

    /**
     * POSTs the envelope and returns what came back, whatever the status: GPS.bg sends SOAP faults with
     * HTTP 500, so deciding what a body means is the parser's job, not the transport's.
     */
    Response post(String envelope) throws IOException, InterruptedException;

    record Response(int status, String body) {}
}
