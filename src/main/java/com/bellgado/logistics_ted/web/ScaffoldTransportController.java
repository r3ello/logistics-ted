package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.repository.HouseRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScaffoldTransportController {

    private final HouseRepository houses;

    public ScaffoldTransportController(HouseRepository houses) {
        this.houses = houses;
    }

    @GetMapping("/scaffold-transport")
    public ResponseEntity<?> findClosestScaffold(
            @RequestParam Integer destinationHouseId,
            @RequestParam(required = false) Double startLat,
            @RequestParam(required = false) Double startLng) {
        House dest = houses.findById(destinationHouseId).orElse(null);
        if (dest == null || dest.getLat() == null || dest.getLng() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Destination house not found or has no coordinates."));
        }

        if (dest.getScaffoldStatus() == ScaffoldStatus.AVAILABLE) {
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("destinationHouse", houseDto(dest));
            already.put("scaffoldHouse", null);
            already.put("distanceKm", 0);
            already.put("mapsUrl", "");
            already.put("alreadyAvailable", true);
            return ResponseEntity.ok(already);
        }

        double destLat = dest.getLat().doubleValue();
        double destLng = dest.getLng().doubleValue();

        List<House> available = houses
            .findByScaffoldStatusAndLatIsNotNullAndLngIsNotNull(ScaffoldStatus.AVAILABLE)
            .stream()
            .filter(h -> !h.getId().equals(dest.getId()))
            .toList();

        if (available.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("destinationHouse", houseDto(dest));
            empty.put("scaffoldHouse", null);
            empty.put("distanceKm", 0);
            empty.put("mapsUrl", "");
            return ResponseEntity.ok(empty);
        }

        House closest = available.stream()
            .min(Comparator.comparingDouble(h -> haversineKm(
                destLat, destLng,
                h.getLat().doubleValue(), h.getLng().doubleValue())))
            .orElseThrow();

        long distKm = Math.round(haversineKm(
            destLat, destLng,
            closest.getLat().doubleValue(), closest.getLng().doubleValue()));

        String mapsUrl;
        if (startLat != null && startLng != null) {
            mapsUrl = "https://www.google.com/maps/dir/"
                + startLat + "," + startLng + "/"
                + closest.getLat() + "," + closest.getLng() + "/"
                + dest.getLat() + "," + dest.getLng();
        } else {
            mapsUrl = "https://www.google.com/maps/dir/"
                + closest.getLat() + "," + closest.getLng() + "/"
                + dest.getLat() + "," + dest.getLng();
        }

        return ResponseEntity.ok(Map.of(
            "destinationHouse", houseDto(dest),
            "scaffoldHouse", houseDto(closest),
            "distanceKm", distKm,
            "mapsUrl", mapsUrl
        ));
    }

    private static Map<String, Object> houseDto(House h) {
        return Map.of(
            "id", h.getId(),
            "name", h.getName(),
            "location", h.getLocation()
        );
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
