package com.feelsolazy.outageguard;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

record Outage(ZonedDateTime start, ZonedDateTime end) {

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public String toString() {
        return DAY_TIME.format(start) + "-" + TIME.format(end);
    }
}
