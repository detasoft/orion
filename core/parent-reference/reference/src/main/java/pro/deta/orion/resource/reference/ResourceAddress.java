package pro.deta.orion.resource.reference;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record ResourceAddress(String raw, ResourceScheme scheme, String body, Map<String, String> parameters) {
    public ResourceAddress {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource address must not be empty");
        }
        scheme = scheme == null ? ResourceScheme.EMPTY : scheme;
        body = body == null ? "" : body;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static ResourceAddress parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Resource address must not be null");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Resource address must not be empty");
        }

        SplitQuery split = splitQuery(value);
        int separator = schemeSeparator(split.address());
        if (separator > 0) {
            String scheme = split.address().substring(0, separator);
            String body = split.address().substring(separator + 1);
            return new ResourceAddress(value, ResourceScheme.of(scheme), body, split.parameters());
        }
        return new ResourceAddress(value, ResourceScheme.EMPTY, split.address(), split.parameters());
    }

    public boolean hasScheme(ResourceScheme expected) {
        return scheme.equals(expected);
    }

    public String addressWithoutParameters() {
        if (scheme.isEmpty()) {
            return body;
        }
        return scheme.value() + ":" + body;
    }

    private static int schemeSeparator(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return -1;
        }
        String candidate = value.substring(0, separator);
        return ResourceScheme.isScheme(candidate) ? separator : -1;
    }

    private static SplitQuery splitQuery(String value) {
        int separator = value.indexOf('?');
        if (separator < 0) {
            return new SplitQuery(value, Map.of());
        }
        return new SplitQuery(value.substring(0, separator), parseQuery(value.substring(separator + 1)));
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            parameters.put(decode(key), decode(value));
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record SplitQuery(String address, Map<String, String> parameters) {
    }
}
