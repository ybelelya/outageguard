package com.feelsolazy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YasnoClientTest {

    private static final String JSON = """
            {"3.1":{
              "today":{"slots":[
                {"start":0,"end":540,"type":"NotPlanned"},
                {"start":540,"end":720,"type":"Definite"},
                {"start":720,"end":1350,"type":"NotPlanned"},
                {"start":1350,"end":1440,"type":"Definite"}],
                "date":"2026-10-28T00:00:00+02:00","status":"ScheduleApplies"},
              "tomorrow":{"slots":[
                {"start":0,"end":180,"type":"Definite"},
                {"start":180,"end":1440,"type":"NotPlanned"}],
                "date":"2026-10-29T00:00:00+02:00","status":"ScheduleApplies"}}}
            """;

    @Test
    void parsesAndMergesAcrossMidnight() throws IOException {
        List<Outage> outages = YasnoClient.parse(JSON, "3.1", Set.of("Definite"));

        assertEquals(2, outages.size());
        assertEquals("28.10 09:00-12:00", outages.get(0).toString());
        assertEquals("2026-10-28T22:30+02:00[Europe/Kyiv]", outages.get(1).start().toString());
        assertEquals("2026-10-29T03:00+02:00[Europe/Kyiv]", outages.get(1).end().toString());
    }

    @Test
    void unknownGroupFails() {
        assertThrows(IOException.class, () -> YasnoClient.parse(JSON, "99.9", Set.of("Definite")));
    }
}
