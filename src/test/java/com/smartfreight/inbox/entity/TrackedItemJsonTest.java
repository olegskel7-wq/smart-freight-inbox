package com.smartfreight.inbox.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the wire contract between port-service and the inbox model.
 *
 * port-service keeps sending its freight-shaped JSON ({@code containerNumber}, ...). The neutral
 * {@link TrackedItem} maps that key to {@code identifier} via {@code @JsonProperty}; if that mapping
 * regresses, the pipeline would silently look up a null identifier. This test fails fast in that case.
 */
class TrackedItemJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialize_portServiceJson_mapsContainerNumberToIdentifier() throws Exception {
        String json = """
                {
                  "containerNumber": "MSCU1234567",
                  "status": "AT_PORT",
                  "location": "Felixstowe Terminal 2"
                }
                """;

        TrackedItem item = mapper.readValue(json, TrackedItem.class);

        assertEquals("MSCU1234567", item.getIdentifier(), "containerNumber JSON key must map to identifier");
        assertEquals("AT_PORT", item.getStatus());
        assertEquals("Felixstowe Terminal 2", item.getLocation());
    }
}
