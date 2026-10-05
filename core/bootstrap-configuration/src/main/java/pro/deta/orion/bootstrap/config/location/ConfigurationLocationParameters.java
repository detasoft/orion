package pro.deta.orion.bootstrap.config.location;

import pro.deta.orion.util.ResourceLocation;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConfigurationLocationParameters {
    private ConfigurationLocationParameters() {
    }

    static Map<String, String> query(ResourceLocation location) {
        String rawQuery = location.uri().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator >= 0 ? part.substring(0, separator) : part;
            String value = separator >= 0 ? part.substring(separator + 1) : "";
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
