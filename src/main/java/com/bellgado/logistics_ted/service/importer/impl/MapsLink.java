package com.bellgado.logistics_ted.service.importer.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Google Maps link pasted into a sheet cell (the staff sheet's {@code Локация}), read offline.
 *
 * <p>A full link ({@code https://www.google.com/maps/place/Samokov/@42.32,23.56,1053m/data=…!3d42.33!4d23.56…})
 * carries the place's name and its pin. The pin is the <b>last</b> {@code !3d<lat>!4d<lng>} pair —
 * a link found through a search also carries the searched area's pin first — and {@code @lat,lng}
 * (the map's centre, not the pin) is only the fallback. A short link ({@code maps.app.goo.gl/…})
 * carries neither and is not followed: the importer makes no network calls.
 *
 * <p>Coordinates are rounded to 6 decimals, the scale of the {@code lat}/{@code lng} columns, so
 * the value read equals the value stored and the next sync sees no change.
 *
 * @param name the place's name ({@code St. Anna University Hospital}), or null
 * @param lat  null for a short link or a link without coordinates
 */
record MapsLink(String name, BigDecimal lat, BigDecimal lng) {

    private static final Pattern FULL = Pattern.compile(
        "^https?://(www\\.)?google\\.[a-z.]+/maps[/?].*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT = Pattern.compile(
        "^https?://(maps\\.app\\.goo\\.gl|goo\\.gl/maps)/\\S*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIN = Pattern.compile("!3d(-?\\d{1,3}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)");
    private static final Pattern CENTRE = Pattern.compile("@(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)");
    private static final Pattern PLACE = Pattern.compile("/maps/place/([^/@?]+)");

    /** The link read, or null if the text is not a Google Maps link. */
    static MapsLink parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (SHORT.matcher(s).matches()) return new MapsLink(null, null, null);
        if (!FULL.matcher(s).matches()) return null;

        String lat = null, lng = null;
        Matcher pin = PIN.matcher(s);
        while (pin.find()) { lat = pin.group(1); lng = pin.group(2); }
        if (lat == null) {
            Matcher centre = CENTRE.matcher(s);
            if (centre.find()) { lat = centre.group(1); lng = centre.group(2); }
        }
        BigDecimal la = coordinate(lat, 90), ln = coordinate(lng, 180);
        if (la == null || ln == null) la = ln = null;

        Matcher place = PLACE.matcher(s);
        String name = place.find() ? decode(place.group(1)) : null;
        return new MapsLink(name, la, ln);
    }

    boolean hasCoordinates() {
        return lat != null;
    }

    private static BigDecimal coordinate(String raw, int limit) {
        if (raw == null) return null;
        BigDecimal d = new BigDecimal(raw).setScale(6, RoundingMode.HALF_UP);
        return d.abs().compareTo(BigDecimal.valueOf(limit)) > 0 ? null : d;
    }

    private static String decode(String segment) {
        try {
            String n = URLDecoder.decode(segment, StandardCharsets.UTF_8).trim().replaceAll("\\s+", " ");
            return n.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith("data=") ? null : n;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
