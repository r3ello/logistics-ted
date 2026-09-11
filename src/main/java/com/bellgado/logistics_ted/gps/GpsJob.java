package com.bellgado.logistics_ted.gps;

import java.time.LocalDate;

/** What one provider slot is spent on. */
public sealed interface GpsJob {

    /** GetStatus("0"): every vehicle's current state in one call. */
    enum Status implements GpsJob { INSTANCE }

    /**
     * VehicleRoutesList for one vehicle and one calendar day in the provider's zone. {@code fullDay}
     * asks for 00:00:00-23:59:59; otherwise the window ends when the call is made (today so far).
     */
    record Routes(String vehicleCode, LocalDate day, boolean fullDay) implements GpsJob {

        boolean sameTarget(Routes other) {
            return vehicleCode.equals(other.vehicleCode) && day.equals(other.day);
        }
    }
}
