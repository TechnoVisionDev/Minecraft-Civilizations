package io.github.empireage.civilizations.database;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.empireage.civilizations.domain.ResourceKey;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonData {
    private static final Gson GSON = new Gson();

    private JsonData() {}

    public static String costs(Map<ResourceKey, Long> values) {
        Map<String, Long> serialized = new LinkedHashMap<>();
        values.forEach((key, value) -> serialized.put(key.serialized(), value));
        return GSON.toJson(serialized);
    }

    public static Map<ResourceKey, Long> costs(String value) {
        if (value == null || value.isBlank()) return Map.of();
        JsonObject object = JsonParser.parseString(value).getAsJsonObject();
        Map<ResourceKey, Long> result = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> result.put(ResourceKey.parse(entry.getKey()), entry.getValue().getAsLong()));
        return Map.copyOf(result);
    }

    public static String booleans(Map<String, Boolean> values) {
        return GSON.toJson(values);
    }

    public static Map<String, Boolean> booleans(String value) {
        if (value == null || value.isBlank()) return Map.of();
        JsonObject object = JsonParser.parseString(value).getAsJsonObject();
        Map<String, Boolean> result = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsBoolean()));
        return Map.copyOf(result);
    }

    public static String object(Map<String, ?> values) {
        return GSON.toJson(values);
    }

    public static String researchCost(long knowledge, Map<ResourceKey, Long> materials) {
        JsonObject root = new JsonObject();
        root.addProperty("knowledge", knowledge);
        JsonObject materialObject = new JsonObject();
        materials.forEach((key, amount) -> materialObject.addProperty(key.serialized(), amount));
        root.add("materials", materialObject);
        return GSON.toJson(root);
    }

    public static ResearchCost researchCost(String value) {
        JsonObject root = JsonParser.parseString(value).getAsJsonObject();
        long knowledge = root.has("knowledge") ? root.get("knowledge").getAsLong() : 0;
        JsonObject materials = root.has("materials") ? root.getAsJsonObject("materials") : new JsonObject();
        Map<ResourceKey, Long> costs = new LinkedHashMap<>();
        materials.entrySet().forEach(entry -> costs.put(ResourceKey.parse(entry.getKey()), entry.getValue().getAsLong()));
        return new ResearchCost(knowledge, Map.copyOf(costs));
    }

    public record ResearchCost(long knowledge, Map<ResourceKey, Long> materials) {}
}
