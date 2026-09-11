package com.bellgado.logistics_ted.gps;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GPS.bg (GPSHunter SOAP) config.
 *
 * <p>{@code enabled=false} (the default) means no client and no poller exist at all; the fleet
 * screens keep serving whatever is already stored. GPS.bg allows one call per 181 s per account and
 * every rejected attempt restarts that timer, so exactly ONE environment may run with it on.
 *
 * <p>{@code user} / {@code password} are secrets: they travel inside every SOAP envelope, so neither
 * the envelope nor this record may reach a log ({@link #toString()} is redacted).
 */
@ConfigurationProperties("gps")
public record GpsHunterProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("https://bg.gpsbg.eu/_services.soap/GPSHunter.php") String endpoint,
    @DefaultValue("") String user,
    @DefaultValue("") String password,
    /** Seconds between provider calls. Raised to {@link #MIN_SLOT_SECONDS} if set lower. */
    @DefaultValue("200") int slotSeconds,
    /** By day, one slot in this many fetches trips instead of live status. */
    @DefaultValue("4") int routeSlotEvery,
    /** GPS.bg timestamps carry no zone; they are Bulgarian local time. */
    @DefaultValue("Europe/Sofia") String providerZone,
    @DefaultValue("60") int requestTimeoutSeconds
) {

    /** GPS.bg rejects calls under 181 s apart (fault 11); keep a margin for clock skew. */
    public static final int MIN_SLOT_SECONDS = 190;

    /** True when a client can actually be built. */
    public boolean usable() {
        return enabled && !endpoint.isBlank() && !user.isBlank() && !password.isBlank();
    }

    public int effectiveSlotSeconds() {
        return Math.max(MIN_SLOT_SECONDS, slotSeconds);
    }

    /** At least 2: with 1 every daytime slot would fetch trips and the live map would never update. */
    public int effectiveRouteSlotEvery() {
        return Math.max(2, routeSlotEvery);
    }

    public ZoneId zone() {
        return ZoneId.of(providerZone);
    }

    @Override
    public String toString() {
        return "GpsHunterProperties[enabled=" + enabled + ", endpoint=" + endpoint + ", user=" + user
            + ", password=<redacted>, slotSeconds=" + slotSeconds + ", routeSlotEvery=" + routeSlotEvery
            + ", providerZone=" + providerZone + ", requestTimeoutSeconds=" + requestTimeoutSeconds + "]";
    }
}
