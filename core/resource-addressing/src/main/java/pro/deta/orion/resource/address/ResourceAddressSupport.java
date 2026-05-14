package pro.deta.orion.resource.address;

import java.util.Map;

final class ResourceAddressSupport {
    private ResourceAddressSupport() {
    }

    static String appendPath(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return path;
        }
        if (base.endsWith("/")) {
            return base + stripLeadingSlash(path);
        }
        return base + "/" + stripLeadingSlash(path);
    }

    static String stripLeadingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    static String stripSchemeSlashes(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    static String queryString(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                result.append('&');
            }
            result.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return result.toString();
    }
}
