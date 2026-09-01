package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

/**
 * Publishes the session-host binaries packaged in the Orion application resources.
 */
public final class SessionHostDownloadRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/session-host*";
    public static final String URL = "/session-host";
    public static final String CONTENT_TYPE = "application/octet-stream";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";

    private static final String RESOURCE_PREFIX = "META-INF/orion/native/session-host/";
    private static final List<String> ALLOWED_METHODS = List.of("GET");
    private static final Map<String, Platform> PLATFORMS = Map.of(
            "x86_64-apple-darwin", new Platform("Darwin", "x86_64"),
            "aarch64-apple-darwin", new Platform("Darwin", "aarch64"),
            "x86_64-unknown-linux-gnu", new Platform("Linux", "x86_64"),
            "aarch64-unknown-linux-gnu", new Platform("Linux", "aarch64"),
            "x86_64-pc-windows-msvc", new Platform("Windows", "x86_64"),
            "aarch64-pc-windows-msvc", new Platform("Windows", "aarch64"));

    private final ClassLoader classLoader;

    @Inject
    public SessionHostDownloadRoute() {
        this(SessionHostDownloadRoute.class.getClassLoader());
    }

    SessionHostDownloadRoute(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public String urlPattern() {
        return URL_PATTERN;
    }

    @Override
    public String authorization() {
        return "anonymous";
    }

    @Override
    public List<String> allowedMethods() {
        return ALLOWED_METHODS;
    }

    @Override
    public void handle(
            HttpServletRequest req,
            HttpServletResponse resp,
            OrionHttpResponseWriter responseWriter) throws IOException, ServletException {
        if (!"GET".equals(req.getMethod().toUpperCase(Locale.ROOT))) {
            resp.setHeader("Allow", "GET");
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        Optional<DownloadRequest> download = requestedDownload(req);
        if (download.isEmpty()) {
            List<DownloadTarget> targets = availableTargets();
            resp.setHeader("Vary", "Accept");
            if (acceptsHtml(req)) {
                writeHtmlIndex(resp, targets);
            } else {
                responseWriter.write(resp, OrionHttpResponse.ok(new DownloadIndex(targets)));
            }
            return;
        }
        String fileName = requestedFileName(req, download.get());
        if (fileName == null) {
            resp.sendError(SC_BAD_REQUEST, "Invalid filename");
            return;
        }
        sendTarget(resp, download.get().target(), fileName);
    }

    private Optional<DownloadRequest> requestedDownload(HttpServletRequest req) {
        String path = routePath(req);
        if (!URL.equals(path) && !(URL + "/").equals(path)) {
            String prefix = URL + "/";
            if (!path.startsWith(prefix)) {
                return Optional.of(new DownloadRequest("", false));
            }
            return Optional.of(new DownloadRequest(path.substring(prefix.length()), false));
        }
        String uname = req.getParameter("uname");
        if (uname == null || uname.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DownloadRequest(targetForUname(uname).orElse(""), true));
    }

    private void sendTarget(HttpServletResponse resp, String target, String fileName) throws IOException {
        if (!PLATFORMS.containsKey(target)) {
            resp.sendError(SC_NOT_FOUND);
            return;
        }
        String resource = resourceFor(target);
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                resp.sendError(SC_NOT_FOUND);
                return;
            }
            resp.setStatus(SC_OK);
            resp.setContentType(CONTENT_TYPE);
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            resp.setHeader("Cache-Control", "no-cache");
            input.transferTo(resp.getOutputStream());
        }
    }

    private List<DownloadTarget> availableTargets() {
        List<DownloadTarget> targets = new ArrayList<>();
        for (Map.Entry<String, Platform> entry : PLATFORMS.entrySet()) {
            String target = entry.getKey();
            if (classLoader.getResource(resourceFor(target)) != null) {
                Platform platform = entry.getValue();
                String fileName = targetFileName(target);
                targets.add(new DownloadTarget(
                        target,
                        platform.os(),
                        platform.architecture(),
                        fileName,
                        URL + "/" + target + "?filename=" + fileName));
            }
        }
        targets.sort((first, second) -> first.target().compareTo(second.target()));
        return List.copyOf(targets);
    }

    private static boolean acceptsHtml(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        if (accept == null) {
            return false;
        }
        for (String range : accept.split(",")) {
            if (acceptsHtmlRange(range)) {
                return true;
            }
        }
        return false;
    }

    private static boolean acceptsHtmlRange(String range) {
        String[] parts = range.split(";");
        if (parts.length == 0 || !"text/html".equalsIgnoreCase(parts[0].strip())) {
            return false;
        }
        for (int index = 1; index < parts.length; index++) {
            String[] parameter = parts[index].split("=", 2);
            if (parameter.length == 2
                    && "q".equalsIgnoreCase(parameter[0].strip())
                    && qualityIsZero(parameter[1])) {
                return false;
            }
        }
        return true;
    }

    private static boolean qualityIsZero(String value) {
        try {
            return Double.parseDouble(value.strip()) == 0;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private static void writeHtmlIndex(
            HttpServletResponse resp,
            List<DownloadTarget> targets) throws IOException {
        resp.setStatus(SC_OK);
        resp.setContentType(HTML_CONTENT_TYPE);
        StringBuilder page = new StringBuilder(
                "<!doctype html><html><head><title>Session hosts</title></head>");
        page.append("<body><h1>Available session hosts</h1><ul>");
        for (DownloadTarget target : targets) {
            page.append("<li><a href=\"")
                    .append(target.url())
                    .append("\">")
                    .append(target.target())
                    .append("</a> (")
                    .append(target.os())
                    .append(", ")
                    .append(target.architecture())
                    .append(", filename=")
                    .append(target.filename())
                    .append(")</li>");
        }
        page.append("</ul></body></html>");
        PrintWriter writer = resp.getWriter();
        writer.write(page.toString());
        writer.flush();
    }

    private static String requestedFileName(HttpServletRequest req, DownloadRequest download) {
        String fileName = req.getParameter("filename");
        if (fileName == null) {
            return download.autoSelected() ? fileName(download.target()) : targetFileName(download.target());
        }
        return isSafeFileName(fileName) ? fileName : null;
    }

    private static boolean isSafeFileName(String fileName) {
        if (fileName.isBlank()) {
            return false;
        }
        for (int index = 0; index < fileName.length(); index++) {
            char character = fileName.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '.'
                    && character != '_'
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> targetForUname(String uname) {
        String[] parts = uname.strip().split("\\s+");
        if (parts.length != 2) {
            return Optional.empty();
        }
        String target = switch (parts[0]) {
            case "Darwin" -> "apple-darwin";
            case "Linux" -> "unknown-linux-gnu";
            default -> null;
        };
        if (target == null) {
            return Optional.empty();
        }
        return switch (parts[1]) {
            case "aarch64", "arm64" -> Optional.of("aarch64-" + target);
            case "x86_64", "amd64" -> Optional.of("x86_64-" + target);
            default -> Optional.empty();
        };
    }

    private static String routePath(HttpServletRequest req) {
        String path = req.getPathInfo();
        return path == null || path.isBlank() ? URL : path;
    }

    private static String resourceFor(String target) {
        return RESOURCE_PREFIX + target + "/session-host";
    }

    private static String fileName(String target) {
        return target.endsWith("windows-msvc") ? "session-host.exe" : "session-host";
    }

    public record DownloadIndex(List<DownloadTarget> targets) {
    }

    private static String targetFileName(String target) {
        return "session-host-" + target + (target.endsWith("windows-msvc") ? ".exe" : "");
    }

    public record DownloadTarget(String target, String os, String architecture, String filename, String url) {
    }

    private record DownloadRequest(String target, boolean autoSelected) {
    }

    private record Platform(String os, String architecture) {
    }
}
