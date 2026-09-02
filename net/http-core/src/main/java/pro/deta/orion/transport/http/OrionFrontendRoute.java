package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public final class OrionFrontendRoute extends AbstractOrionHttpRoute {
    public static final String URL_PATTERN = "/*";
    private static final String UI_ALIAS = "/ui";
    private static final String RESOURCE_ROOT = "META-INF/orion/frontend/";
    private static final String INDEX = "index.html";
    private static final String NO_CACHE = "no-cache";
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("js", "application/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"));

    @Inject
    public OrionFrontendRoute() {
        super(URL_PATTERN, "GET", "HEAD");
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) throws IOException {
        String resourceName = resourceName(req);
        if (resourceName == null) {
            return OrionHttpResponse.empty(SC_NOT_FOUND);
        }

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE_ROOT + resourceName)) {
            if (input == null) {
                return OrionHttpResponse.empty(SC_NOT_FOUND);
            }
            String cacheControl = INDEX.equals(resourceName) ? NO_CACHE : IMMUTABLE_CACHE;
            return OrionHttpResponse.resource(SC_OK, input.readAllBytes(), contentType(resourceName))
                    .withHeader("Cache-Control", cacheControl);
        }
    }

    private static String resourceName(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        if ("/".equals(path) || UI_ALIAS.equals(path) || (UI_ALIAS + "/").equals(path)) {
            return INDEX;
        }
        if (path == null || !path.startsWith("/")) {
            return null;
        }

        String relativePath = path.substring(1);
        if (relativePath.isBlank()
                || relativePath.contains("..")
                || relativePath.contains("\\")
                || relativePath.startsWith("/")) {
            return null;
        }
        return relativePath;
    }

    private static String contentType(String resourceName) {
        int extensionStart = resourceName.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == resourceName.length() - 1) {
            return "application/octet-stream";
        }
        String extension = resourceName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
