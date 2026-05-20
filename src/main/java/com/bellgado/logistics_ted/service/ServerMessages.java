package com.bellgado.logistics_ted.service;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Mirrors the serverMsgs object from server.js — keeps i18n for the 4 error strings. */
@Component
public class ServerMessages {

    private static final Map<String, Map<String, String>> MSGS = Map.of(
        "en", Map.of(
            "noStartHouse",  "Please pick a starting location on the map.",
            "noDestHouse",   "Please select a destination house.",
            "houseNotFound", "Destination house not found.",
            "noValidQty",    "Order has no valid quantities."
        ),
        "bg", Map.of(
            "noStartHouse",  "Моля изберете начална локация на картата.",
            "noDestHouse",   "Моля изберете целева къща.",
            "houseNotFound", "Целевата къща не е намерена.",
            "noValidQty",    "Поръчката няма валидни количества."
        )
    );

    public String get(String lang, String key) {
        Map<String, String> bundle = MSGS.getOrDefault(lang, MSGS.get("en"));
        String value = bundle.get(key);
        return value != null ? value : MSGS.get("en").get(key);
    }
}
