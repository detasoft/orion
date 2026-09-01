package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.schema.config.GitPackfileUriConfig;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriBuilder;
import pro.deta.orion.git.nativestorage.upload.PublishedPackfileUriSource;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.transport.git.auth.AuthenticatedRepositoryAccessHook;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE;

public class OrionGitRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*";
    private static final List<String> ALLOWED_METHODS = List.of("GET", "HEAD", "POST");
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_CACHE = "no-cache, max-age=0, must-revalidate";
    private static final String EXPIRES = "Expires";
    private static final String NO_CACHE_EXPIRES = "Fri, 01 Jan 1980 00:00:00 GMT";
    private static final String PRAGMA = "Pragma";
    private static final String GIT_PROTOCOL_HEADER = "Git-Protocol";
    private static final String UPLOAD_ADVERTISEMENT_TYPE = "application/x-git-upload-pack-advertisement";
    private static final String RECEIVE_ADVERTISEMENT_TYPE = "application/x-git-receive-pack-advertisement";
    private static final String UPLOAD_REQUEST_TYPE = "application/x-git-upload-pack-request";
    private static final String RECEIVE_REQUEST_TYPE = "application/x-git-receive-pack-request";
    private static final String UPLOAD_RESULT_TYPE = "application/x-git-upload-pack-result";
    private static final String RECEIVE_RESULT_TYPE = "application/x-git-receive-pack-result";

    private final GitNativeRepositoryService repositoryService;
    private final GitTransportConfig gitTransportConfig;

    @Inject
    public OrionGitRoute(
            GitNativeRepositoryService repositoryService,
            GitTransportConfig gitTransportConfig) {
        this.repositoryService = Objects.requireNonNull(
                repositoryService,
                "repositoryService");
        this.gitTransportConfig = Objects.requireNonNull(gitTransportConfig, "gitTransportConfig");
    }

    @Override
    public String urlPattern() {
        return URL_PATTERN;
    }

    @Override
    public String authorization() {
        return "git";
    }

    @Override
    public List<String> allowedMethods() {
        return ALLOWED_METHODS;
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse resp, OrionHttpResponseWriter responseWriter) throws IOException, ServletException {
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        String endpointAllow = endpointAllow(stripRoutePrefix(routePath(req)));
        if (endpointAllow != null && !endpointAllows(endpointAllow, method)) {
            resp.setHeader("Allow", endpointAllow);
            setNoCacheHeaders(resp);
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (!ALLOWED_METHODS.contains(method)) {
            resp.setHeader("Allow", String.join(", ", ALLOWED_METHODS));
            setNoCacheHeaders(resp);
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        handleNative(req, resp);
    }

    private void handleNative(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<NativeHttpRequest> nativeRequest = nativeRequest(req);
        if (nativeRequest.isEmpty()) {
            resp.sendError(SC_BAD_REQUEST);
            return;
        }
        NativeHttpRequest request = nativeRequest.get();
        try {
            if (request.discovery()) {
                handleNativeDiscovery(req, resp, request);
            } else {
                handleNativePost(req, resp, request);
            }
        } catch (GitNativeRepositoryAccessHook.AccessDeniedException error) {
            resp.sendError(SC_FORBIDDEN);
        } catch (IllegalArgumentException error) {
            resp.sendError(SC_BAD_REQUEST, error.getMessage());
        } catch (IOException error) {
            if (causedByAccessDenied(error)) {
                resp.sendError(SC_FORBIDDEN);
            } else if (missingRepository(error)) {
                resp.sendError(SC_NOT_FOUND);
            } else {
                throw error;
            }
        }
    }

    private void handleNativeDiscovery(
            HttpServletRequest req,
            HttpServletResponse resp,
            NativeHttpRequest request) throws IOException {
        resp.setStatus(SC_OK);
        resp.setContentType(advertisementContentType(request.service()));
        setNoCacheHeaders(resp);
        try (InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(req.getInputStream())) {
            OutputStreamBufferedByteOutput output = new OutputStreamBufferedByteOutput(resp.getOutputStream());
            GitWireBootstrap bootstrap = getGitWireBootstrap(req, request, input, output);
            NativePackfileUriSourceFactory packfileUriSourceFactory = packfileUriSourceFactory(req);
            if (bootstrap.data()
                    .getProtocolVersion()
                    .filter(InitialRequestData.ProtocolVersion.V2::equals)
                    .isEmpty()) {
                writeServiceAnnouncement(bootstrap.wire(), request.service());
            }
            SecurityContext securityContext = securityContextFrom(req);
            session(securityContext, packfileUriSourceFactory, bootstrap.wire()).advertise(bootstrap.data());
        }
    }

    private void handleNativePost(
            HttpServletRequest req,
            HttpServletResponse resp,
            NativeHttpRequest request) throws IOException {
        if (!contentTypeMatches(req.getContentType(), requestContentType(request.service()))) {
            setNoCacheHeaders(resp);
            resp.sendError(SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        resp.setStatus(SC_OK);
        resp.setContentType(resultContentType(request.service()));
        setNoCacheHeaders(resp);
        try (InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(req.getInputStream())) {
            OutputStreamBufferedByteOutput output = new OutputStreamBufferedByteOutput(resp.getOutputStream());
            GitWireBootstrap bootstrap = getGitWireBootstrap(req, request, input, output);
            NativePackfileUriSourceFactory packfileUriSourceFactory = packfileUriSourceFactory(req);
            SecurityContext securityContext = securityContextFrom(req);
            session(securityContext, packfileUriSourceFactory, bootstrap.wire())
                    .serveSmartHttpPost(bootstrap.data());
        }
    }

    private static @NonNull GitWireBootstrap getGitWireBootstrap(HttpServletRequest req, NativeHttpRequest request, InputStreamBufferedByteInput input, OutputStreamBufferedByteOutput output) {
        GitWireBootstrap bootstrap = GitWireBootstrap.smartHttp(
                input,
                output,
                request.service(),
                request.repositoryPath(),
                requestHost(req),
                req.getHeader(GIT_PROTOCOL_HEADER));
        return bootstrap;
    }

    private GitBlockingWireSession session(
            SecurityContext securityContext,
            NativePackfileUriSourceFactory packfileUriSourceFactory,
            GitBlockingWireTransport wire) {
        return new GitBlockingWireSession(
                repositoryService,
                new AuthenticatedRepositoryAccessHook(
                        securityContext,
                        true),
                GitWireConfiguration.allSupported(),
                packfileUriSourceFactory,
                wire);
    }

    private NativePackfileUriSourceFactory packfileUriSourceFactory(HttpServletRequest request) {
        GitPackfileUriConfig packfileUri = gitTransportConfig == null ? null : gitTransportConfig.getPackfileUri();
        Optional<String> baseUri = OrionGitPackfileUriBaseResolver.resolve(request, packfileUri);
        if (baseUri.isEmpty()) {
            return NativePackfileUriSourceFactory.NONE;
        }
        return (data, repository) -> new PublishedPackfileUriSource(
                repository,
                packId -> NativePackfileUriBuilder.packUri(
                        baseUri.get(),
                        data.getRepositoryPath(),
                        packId));
    }

    private Optional<NativeHttpRequest> nativeRequest(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if ("HEAD".equals(method)) {
            method = "GET";
        }
        String path = stripRoutePrefix(routePath(request));
        if ("GET".equals(method) && path.endsWith("/info/refs")) {
            InitialRequestService service = serviceParameter(request);
            if (service == null) {
                return Optional.empty();
            }
            String repositoryPath = path.substring(0, path.length() - "/info/refs".length());
            return Optional.of(NativeHttpRequest.discovery(service, repositoryPath));
        }
        if ("POST".equals(method) && path.endsWith("/git-upload-pack")) {
            String repositoryPath = path.substring(0, path.length() - "/git-upload-pack".length());
            return Optional.of(NativeHttpRequest.post(InitialRequestService.UPLOAD_PACK, repositoryPath));
        }
        if ("POST".equals(method) && path.endsWith("/git-receive-pack")) {
            String repositoryPath = path.substring(0, path.length() - "/git-receive-pack".length());
            return Optional.of(NativeHttpRequest.post(InitialRequestService.RECEIVE_PACK, repositoryPath));
        }
        return Optional.empty();
    }

    private static InitialRequestService serviceParameter(HttpServletRequest request) {
        try {
            String service = request.getParameter("service");
            if (service == null || service.isBlank()) {
                return null;
            }
            return InitialRequestService.fromWireName(service);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String requestHost(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            return host;
        }
        return null;
    }

    private static void writeServiceAnnouncement(
            GitBlockingWireTransport wire,
            InitialRequestService service) throws IOException {
        wire.writeTextLine("# service=" + service.wireName());
        wire.writeFlush();
    }

    private static String advertisementContentType(InitialRequestService service) {
        return service == InitialRequestService.UPLOAD_PACK ? UPLOAD_ADVERTISEMENT_TYPE : RECEIVE_ADVERTISEMENT_TYPE;
    }

    private static String requestContentType(InitialRequestService service) {
        return service == InitialRequestService.UPLOAD_PACK ? UPLOAD_REQUEST_TYPE : RECEIVE_REQUEST_TYPE;
    }

    private static String resultContentType(InitialRequestService service) {
        return service == InitialRequestService.UPLOAD_PACK ? UPLOAD_RESULT_TYPE : RECEIVE_RESULT_TYPE;
    }

    private static boolean contentTypeMatches(String actual, String expected) {
        return expected.equals(actual);
    }

    private static void setNoCacheHeaders(HttpServletResponse response) {
        response.setHeader(EXPIRES, NO_CACHE_EXPIRES);
        response.setHeader(PRAGMA, "no-cache");
        response.setHeader(CACHE_CONTROL, NO_CACHE);
    }

    private static String endpointAllow(String path) {
        if (path.endsWith("/info/refs")) {
            return "GET, HEAD";
        }
        if (path.endsWith("/git-upload-pack")
                || path.endsWith("/git-receive-pack")) {
            return "POST";
        }
        return null;
    }

    private static boolean endpointAllows(String allow, String method) {
        if ("POST".equals(allow)) {
            return "POST".equals(method);
        }
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static boolean causedByAccessDenied(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof GitNativeRepositoryAccessHook.AccessDeniedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean missingRepository(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Native repository does not exist")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record NativeHttpRequest(
            boolean discovery,
            InitialRequestService service,
            String repositoryPath) {

        private static NativeHttpRequest discovery(
                InitialRequestService service,
                String repositoryPath) {
            return new NativeHttpRequest(true, service, repositoryPath);
        }

        private static NativeHttpRequest post(
                InitialRequestService service,
                String repositoryPath) {
            return new NativeHttpRequest(false, service, repositoryPath);
        }
    }

    private static SecurityContext securityContextFrom(HttpServletRequest req) {
        Object attribute = req.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (attribute instanceof SecurityContext securityContext) {
            return securityContext;
        }
        return SecurityContext.createContext().withRequestId(req.toString());
    }

    private static String routePath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path != null && !path.isBlank()) {
            return path;
        }
        path = req.getRequestURI();
        String contextPath = req.getContextPath();
        if (path != null && contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path != null && !path.isBlank()) {
            return path;
        }
        return "/";
    }

    private static String stripRoutePrefix(String path) {
        if (path == null) {
            return null;
        }
        if ("/r".equals(path)) {
            return "/";
        }
        if (path.startsWith("/r/")) {
            return path.substring("/r".length());
        }
        return path;
    }

}
