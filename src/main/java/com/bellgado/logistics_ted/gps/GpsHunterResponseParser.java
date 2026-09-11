package com.bellgado.logistics_ted.gps;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads a GPSHunter SOAP response into one {@code field -> value} map per returned struct.
 *
 * <p>The response is rpc/encoded SOAP 1.1:
 * {@code Envelope > Body > HunterSoapServiceResponse > HunterSoapServiceReturn > item* > <Field>value</Field>*}.
 * Only local names are compared — never namespaces, never {@code xsi:type}.
 *
 * <p>GetStatus wraps string values in literal double quotes ({@code "51712"}, {@code "МАСТЪР 2"});
 * VehicleRoutesList does not. Values come out unquoted either way.
 *
 * <p>Errors arrive as a SOAP Fault whose {@code faultcode} is the provider's number, e.g.
 * {@code <faultcode>6</faultcode><faultstring>Error !!!</faultstring>} (captured live).
 */
final class GpsHunterResponseParser {

    private static final XMLInputFactory FACTORY = createFactory();

    private GpsHunterResponseParser() {
    }

    static List<Map<String, String>> parse(int httpStatus, String body) {
        if (body == null || body.isBlank()) {
            throw new GpsHunterException(GpsHunterException.TRANSPORT, "HTTP " + httpStatus + " with an empty body");
        }
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(body));
            try {
                return read(r, httpStatus);
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new GpsHunterException(GpsHunterException.TRANSPORT,
                "HTTP " + httpStatus + ": response is not XML (" + e.getMessage() + ")");
        }
    }

    private static List<Map<String, String>> read(XMLStreamReader r, int httpStatus) throws XMLStreamException {
        int depth = 0;
        int returnDepth = -1;
        List<Map<String, String>> records = null;
        Map<String, String> current = null;
        String field = null;
        StringBuilder text = new StringBuilder();
        String faultCode = null;
        String faultString = null;

        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String name = r.getLocalName();
                if (returnDepth < 0 && name.endsWith("Return")) {
                    returnDepth = depth;
                    records = new ArrayList<>();
                } else if (returnDepth > 0 && depth == returnDepth + 1) {
                    current = new LinkedHashMap<>();
                } else if (returnDepth > 0 && depth == returnDepth + 2) {
                    field = name;
                    text.setLength(0);
                } else if (returnDepth < 0 && ("faultcode".equals(name) || "faultstring".equals(name))) {
                    field = name;
                    text.setLength(0);
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (field != null) text.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = r.getLocalName();
                if (returnDepth > 0 && depth == returnDepth + 2 && field != null) {
                    current.put(field, GpsValues.unquote(text.toString().trim()));
                    field = null;
                } else if (returnDepth > 0 && depth == returnDepth + 1 && current != null) {
                    records.add(current);
                    current = null;
                } else if ("faultcode".equals(name) && field != null) {
                    faultCode = text.toString().trim();
                    field = null;
                } else if ("faultstring".equals(name) && field != null) {
                    faultString = text.toString().trim();
                    field = null;
                }
                depth--;
            }
        }

        if (faultCode != null) throw fault(faultCode, faultString);
        if (records == null) {
            throw new GpsHunterException(GpsHunterException.TRANSPORT,
                "HTTP " + httpStatus + ": no HunterSoapServiceReturn in the response");
        }
        return records;
    }

    private static GpsHunterException fault(String code, String text) {
        String bare = code.contains(":") ? code.substring(code.indexOf(':') + 1) : code;
        try {
            return new GpsHunterException(Integer.parseInt(bare.trim()), text);
        } catch (NumberFormatException e) {
            return new GpsHunterException(GpsHunterException.TRANSPORT,
                "SOAP fault " + code + (text == null || text.isBlank() ? "" : ": " + text));
        }
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        // Remote, untrusted XML: no DTDs and no external entities (XXE).
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }
}
