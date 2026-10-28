package com.feelsolazy.outageguard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches the planned outage schedule from the public Yasno API.
 * Response: { "3.1": { "today": {date, status, slots:[{start,end,type}]}, "tomorrow": {...} }, ... }
 * where start/end are minutes from local (Kyiv) midnight.
 */
@Slf4j
final class YasnoClient {

    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String URL =
            "https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/%d/dsos/%d/planned-outages";

    private final Config cfg;
    private final Path cacheFile;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    YasnoClient(Config cfg) {
        this.cfg = cfg;
        this.cacheFile = cfg.dir().resolve("schedule-cache-%d-%d.json".formatted(cfg.region(), cfg.dso()));
    }

    List<Outage> fetch() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL.formatted(cfg.region(), cfg.dso())))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "OutageGuard/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Yasno API returned HTTP " + resp.statusCode());
        }
        List<Outage> outages = parse(resp.body(), cfg.group(), cfg.outageTypes());
        Files.writeString(cacheFile, resp.body());
        return outages;
    }

    /** Last successfully fetched schedule, used when the network is not up yet after boot. */
    Optional<List<Outage>> loadCached() {
        try {
            if (Files.exists(cacheFile)) {
                return Optional.of(parse(Files.readString(cacheFile), cfg.group(), cfg.outageTypes()));
            }
        } catch (Exception e) {
            log.warn("Ignoring unreadable cache {}", cacheFile, e);
        }
        return Optional.empty();
    }

    static List<Outage> parse(String json, String group, Set<String> outageTypes) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject groupObj = root.getAsJsonObject(group);
        if (groupObj == null) {
            throw new IOException("Group '" + group + "' not found. Available: " + root.keySet());
        }

        List<Outage> slots = new ArrayList<>();
        for (String day : List.of("today", "tomorrow")) {
            JsonObject dayObj = groupObj.getAsJsonObject(day);
            if (dayObj == null || !dayObj.has("date") || !dayObj.has("slots")) {
                continue;
            }
            LocalDate date = OffsetDateTime.parse(dayObj.get("date").getAsString())
                    .atZoneSameInstant(KYIV).toLocalDate();
            for (JsonElement el : dayObj.getAsJsonArray("slots")) {
                JsonObject slot = el.getAsJsonObject();
                if (outageTypes.contains(slot.get("type").getAsString())) {
                    slots.add(new Outage(at(date, slot.get("start").getAsInt()), at(date, slot.get("end").getAsInt())));
                }
            }
        }
        return merge(slots);
    }

    /** Minutes are wall-clock time, so DST-change days are handled via LocalDateTime. */
    private static ZonedDateTime at(LocalDate date, int minutes) {
        return date.atStartOfDay().plusMinutes(minutes).atZone(KYIV);
    }

    /** Joins adjacent slots (e.g. 22:30–24:00 today + 00:00–03:00 tomorrow) into one outage. */
    private static List<Outage> merge(List<Outage> slots) {
        slots.sort(Comparator.comparing(Outage::start));
        List<Outage> merged = new ArrayList<>();
        for (Outage o : slots) {
            if (!merged.isEmpty()) {
                Outage last = merged.getLast();
                if (!o.start().isAfter(last.end())) {
                    if (o.end().isAfter(last.end())) {
                        merged.set(merged.size() - 1, new Outage(last.start(), o.end()));
                    }
                    continue;
                }
            }
            merged.add(o);
        }
        return List.copyOf(merged);
    }
}
