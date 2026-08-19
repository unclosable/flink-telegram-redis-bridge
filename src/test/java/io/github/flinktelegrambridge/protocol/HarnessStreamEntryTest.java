package io.github.flinktelegrambridge.protocol;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HarnessStreamEntryTest {

    @Test
    void prefersJsonField() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("json", "{\"a\":1}");
        body.put("other", "ignored");
        assertEquals("{\"a\":1}", HarnessStreamEntry.fromBody("1-0", body).body());
    }

    @Test
    void fallsBackToFirstField() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("payload", "hello");
        assertEquals("hello", HarnessStreamEntry.fromBody("1-1", body).body());
    }

    @Test
    void handlesEmptyBody() {
        assertNull(HarnessStreamEntry.fromBody("1-2", null).body());
        assertNull(HarnessStreamEntry.fromBody("1-3", Map.of()).body());
    }
}
