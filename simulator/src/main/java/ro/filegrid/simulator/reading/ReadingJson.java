package ro.filegrid.simulator.reading;

import java.math.BigDecimal;

import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wire format of a reading. Built as a JSON tree (not via data binding) so the
 * anomaly injector can corrupt it field by field.
 */
public final class ReadingJson {

    /** Decimals are always written plainly (0.412, never 4.12E-1). */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private ReadingJson() {}

    public static ObjectNode toTree(MeterReading r) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("readingId", r.readingId().toString());
        root.put("meterId", r.meterId());
        root.put("sequence", r.sequence());
        root.put("region", r.region().name());
        root.put("measuredAt", r.measuredAt().toString());
        root.put("intervalMinutes", r.intervalMinutes());
        root.put("firmware", r.firmware());
        ArrayNode values = root.putArray("values");
        for (MeterReading.Value v : r.values()) {
            values.addObject()
                    .put("at", v.at().toString())
                    .put("activeEnergyKwh", BigDecimal.valueOf(v.energyWh(), 3))
                    .put("registerKwh", BigDecimal.valueOf(v.registerWh(), 3))
                    .put("voltageV", BigDecimal.valueOf(v.voltageDeciV(), 1));
        }
        return root;
    }

    public static byte[] toBytes(ObjectNode tree) {
        return MAPPER.writeValueAsBytes(tree);
    }

    public static byte[] toBytes(MeterReading r) {
        return toBytes(toTree(r));
    }
}
