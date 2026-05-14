package pro.deta.orion.resource.address;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class ResourceAddressParser {
    private ResourceAddressParser() {
    }

    static ResourceExpression parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Resource address must not be null");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Resource address must not be empty");
        }

        SplitQuery split = splitTopLevelQuery(value);
        String address = split.address();
        int openParenthesis = topLevelOpenParenthesis(address);
        if (openParenthesis > 0) {
            String schemeValue = address.substring(0, openParenthesis);
            if (isScheme(schemeValue)) {
                int closeParenthesis = matchingCloseParenthesis(address, openParenthesis);
                String nestedRaw = address.substring(openParenthesis + 1, closeParenthesis);
                if (nestedRaw.isBlank()) {
                    throw new IllegalArgumentException("Nested resource address must not be empty: " + raw);
                }
                String suffix = stripLeadingSlash(address.substring(closeParenthesis + 1));
                return new ResourceExpression(
                        value,
                        ResourceScheme.of(schemeValue),
                        null,
                        parse(nestedRaw),
                        suffix,
                        split.parameters());
            }
        }

        int schemeSeparator = schemeSeparator(address);
        if (schemeSeparator > 0) {
            String schemeValue = address.substring(0, schemeSeparator);
            String body = address.substring(schemeSeparator + 1);
            ResourceExpression nested = nested(body);
            return new ResourceExpression(
                    value,
                    ResourceScheme.of(schemeValue),
                    nested == null ? body : null,
                    nested,
                    "",
                    split.parameters());
        }

        return new ResourceExpression(value, ResourceScheme.EMPTY, address, null, "", split.parameters());
    }

    static boolean isScheme(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static ResourceExpression nested(String body) {
        if (body == null || body.isBlank() || body.startsWith("//") || body.startsWith("/")) {
            return null;
        }
        int separator = schemeSeparator(body);
        if (separator <= 0) {
            return null;
        }
        return parse(body);
    }

    private static int schemeSeparator(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return -1;
        }
        String candidate = value.substring(0, separator);
        return isScheme(candidate) ? separator : -1;
    }

    private static int topLevelOpenParenthesis(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                return i;
            }
            if (c == ':' || c == '/' || c == '?') {
                return -1;
            }
        }
        return -1;
    }

    private static int matchingCloseParenthesis(String value, int openParenthesis) {
        int depth = 0;
        for (int i = openParenthesis; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Unclosed nested resource address: " + value);
    }

    private static SplitQuery splitTopLevelQuery(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '?' && depth == 0) {
                return new SplitQuery(value.substring(0, i), parseQuery(value.substring(i + 1)));
            }
        }
        return new SplitQuery(value, Map.of());
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
        return Map.copyOf(parameters);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String stripLeadingSlash(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private record SplitQuery(String address, Map<String, String> parameters) {
    }
}
