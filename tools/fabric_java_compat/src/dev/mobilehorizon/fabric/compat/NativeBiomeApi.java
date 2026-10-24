package dev.mobilehorizon.fabric.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Typed client for Horizon's native biome backend.
 *
 * This is deliberately not net.minecraft.world.level.biome.Biome or Level.
 * Facade adapters may depend on this SPI, but must not expose its DTOs as native
 * Minecraft objects or imply support for biome tags/modded biome registration.
 */
public final class NativeBiomeApi {
    public record BlockPos(int x, int y, int z) {}
    public record BiomeDefinition(int numericId, String id, double temperature, double downfall, boolean precipitation) {}
    public record BiomeSample(BlockPos blockPosition, BlockPos quartPosition, Optional<String> biomeId) {}
    public record SearchResult(boolean found, String biomeId, Optional<BlockPos> position, OptionalLong distanceBlocks) {}

    private NativeBiomeApi() {}

    public static List<BiomeDefinition> catalog() {
        Map<String,Object> response = NativeBridgeClient.query("fabric.biome.catalog", Map.of());
        Object raw = response.get("biomes");
        if (!(raw instanceof List<?> rows)) throw new IllegalStateException("Biome catalog response is missing its biomes array");
        List<BiomeDefinition> result = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Map<String,Object> item = object(row, "biome catalog entry");
            result.add(new BiomeDefinition(
                    integer(item.get("numeric_id"), "numeric_id"),
                    text(item.get("id"), "id"),
                    decimal(item.get("temperature"), "temperature"),
                    decimal(item.get("downfall"), "downfall"),
                    bool(item.get("precipitation"), "precipitation")));
        }
        return List.copyOf(result);
    }

    public static List<BiomeSample> sample(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty() || positions.size() > 256) {
            throw new IllegalArgumentException("Biome sampling requires 1..256 positions");
        }
        List<List<Integer>> encoded = positions.stream()
                .map(p -> List.of(p.x(), p.y(), p.z()))
                .toList();
        Map<String,Object> response = NativeBridgeClient.query("fabric.biome.sample", Map.of("positions", encoded));
        Object raw = response.get("samples");
        if (!(raw instanceof List<?> rows) || rows.size() != positions.size()) {
            throw new IllegalStateException("Biome sample response count does not match the request");
        }
        List<BiomeSample> result = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Map<String,Object> item = object(row, "biome sample entry");
            Object id = item.get("biome_id");
            Optional<String> biomeId = id == null ? Optional.empty() : Optional.of(text(id, "biome_id"));
            result.add(new BiomeSample(position(item.get("block_position")), position(item.get("quart_position")), biomeId));
        }
        return List.copyOf(result);
    }

    public static SearchResult findClosest(String biomeId, BlockPos origin, int radius, int horizontalStep, int verticalStep) {
        if (biomeId == null || biomeId.isBlank() || biomeId.length() > 256) throw new IllegalArgumentException("Invalid biome identifier");
        if (origin == null) throw new IllegalArgumentException("Biome search origin is required");
        if (radius < 1 || radius > 6400 || horizontalStep < 1 || horizontalStep > 64 || verticalStep < 1 || verticalStep > 256) {
            throw new IllegalArgumentException("Biome search bounds are outside the native adapter limits");
        }
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("biome_id", biomeId);
        payload.put("origin", List.of(origin.x(), origin.y(), origin.z()));
        payload.put("radius", radius);
        payload.put("horizontal_step", horizontalStep);
        payload.put("vertical_step", verticalStep);
        Map<String,Object> response = NativeBridgeClient.query("fabric.biome.search", payload);
        boolean found = bool(response.get("found"), "found");
        if (!found) return new SearchResult(false, text(response.getOrDefault("biome_id_requested", biomeId), "biome_id_requested"), Optional.empty(), OptionalLong.empty());
        BlockPos position = position(response.get("position"));
        long distance = integer(response.get("distance_blocks"), "distance_blocks");
        return new SearchResult(true, text(response.get("biome_id"), "biome_id"), Optional.of(position), OptionalLong.of(distance));
    }

    private static BlockPos position(Object value) {
        if (!(value instanceof List<?> values) || values.size() != 3) throw new IllegalStateException("Expected three-coordinate native position");
        return new BlockPos(integer(values.get(0), "x"), integer(values.get(1), "y"), integer(values.get(2), "z"));
    }
    private static Map<String,Object> object(Object value, String label) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalStateException("Invalid " + label);
        Map<String,Object> result = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalStateException("Invalid key in " + label);
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static String text(Object value, String field) {
        if (!(value instanceof String string)) throw new IllegalStateException("Invalid native field: " + field);
        return string;
    }
    private static int integer(Object value, String field) {
        if (!(value instanceof Number number)) throw new IllegalStateException("Invalid native field: " + field);
        long n = number.longValue();
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalStateException("Native integer out of range: " + field);
        return (int)n;
    }
    private static double decimal(Object value, String field) {
        if (!(value instanceof Number number)) throw new IllegalStateException("Invalid native field: " + field);
        double n = number.doubleValue();
        if (!Double.isFinite(n)) throw new IllegalStateException("Non-finite native number: " + field);
        return n;
    }
    private static boolean bool(Object value, String field) {
        if (!(value instanceof Boolean result)) throw new IllegalStateException("Invalid native field: " + field);
        return result;
    }
}
