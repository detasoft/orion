package pro.deta.orion.transport.http;

import io.netty.buffer.UnpooledByteBufAllocator;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.schema.config.GitPackfileUriConfig;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriBuilder;
import pro.deta.orion.git.nativestorage.upload.PublishedPackfileUriSource;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.pkt.GitBufferedByteTransportAdapter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public class OrionGitRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*";
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST");
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_CACHE = "no-cache";
    private static final String GIT_PROTOCOL_HEADER = "Git-Protocol";
    private static final String UPLOAD_ADVERTISEMENT_TYPE =
            "application/x-git-upload-pack-advertisement";
    private static final String RECEIVE_ADVERTISEMENT_TYPE =
            "application/x-git-receive-pack-advertisement";
    private static final String UPLOAD_REQUEST_TYPE =
            "application/x-git-upload-pack-request";
    private static final String RECEIVE_REQUEST_TYPE =
            "application/x-git-receive-pack-request";
    private static final String UPLOAD_RESULT_TYPE =
            "application/x-git-upload-pack-result";
    private static final String RECEIVE_RESULT_TYPE =
            "application/x-git-receive-pack-result";

    private final NativeGitRepositoryProvider nativeRepositoryProvider;
    private final GitTransportConfig gitTransportConfig;

    @Inject
    public OrionGitRoute(
            NativeGitRepositoryProvider nativeRepositoryProvider,
            GitTransportConfig gitTransportConfig) {
        this.nativeRepositoryProvider = Objects.requireNonNull(
                nativeRepositoryProvider,
                "nativeRepositoryProvider");
        this.gitTransportConfig = Objects.requireNonNull(
                gitTransportConfig,
                "gitTransportConfig");
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
    public void handle(
            HttpServletRequest req,
            HttpServletResponse resp,
            OrionHttpResponseWriter responseWriter)
            throws IOException, ServletException {
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            resp.setHeader("Allow", String.join(", ", ALLOWED_METHODS));
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        handleNative(req, resp);
    }

    private void handleNative(
            HttpServletRequest req,
            HttpServletResponse resp)
            throws IOException {
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
            NativeHttpRequest request)
            throws IOException {
        resp.setStatus(SC_OK);
        resp.setContentType(advertisementContentType(request.service()));
        resp.setHeader(CACHE_CONTROL, NO_CACHE);
        JettyBufferedByteOutput output =
                new JettyBufferedByteOutput(resp.getOutputStream());
        writeServiceAnnouncement(output, request.service());
        session(req, null, output).advertise(request.data());
    }

    private void handleNativePost(
            HttpServletRequest req,
            HttpServletResponse resp,
            NativeHttpRequest request)
            throws IOException {
        if (!contentTypeMatches(
                req.getContentType(),
                requestContentType(request.service()))) {
            resp.sendError(SC_BAD_REQUEST);
            return;
        }
        resp.setStatus(SC_OK);
        resp.setContentType(resultContentType(request.service()));
        resp.setHeader(CACHE_CONTROL, NO_CACHE);
        try (JettyBufferedByteInput input = new JettyBufferedByteInput(
                req.getInputStream(),
                UnpooledByteBufAllocator.DEFAULT,
                GitBlockingWireSession.DEFAULT_INPUT_BUFFER_SIZE)) {
            session(
                    req,
                    input,
                    new JettyBufferedByteOutput(resp.getOutputStream()))
                    .serveSmartHttpPost(request.data());
        }
    }

    private GitBlockingWireSession session(
            HttpServletRequest request,
            JettyBufferedByteInput input,
            JettyBufferedByteOutput output) {
        return new GitBlockingWireSession(
                UnpooledByteBufAllocator.DEFAULT,
                nativeRepositoryProvider,
                new NativeHttpRepositoryAccessHook(securityContextFrom(request)),
                GitWireConfiguration.allSupported(),
                packfileUriSourceFactory(request),
                input,
                output);
    }

    private NativePackfileUriSourceFactory packfileUriSourceFactory(
            HttpServletRequest request) {
        GitPackfileUriConfig packfileUri = gitTransportConfig == null
                ? null
                : gitTransportConfig.getPackfileUri();
        Optional<String> baseUri =
                OrionGitPackfileUriBaseResolver.resolve(request, packfileUri);
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

    private Optional<NativeHttpRequest> nativeRequest(
            HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = stripRoutePrefix(routePath(request));
        if ("GET".equals(method) && path.endsWith("/info/refs")) {
            InitialRequestService service = serviceParameter(request);
            if (service == null) {
                return Optional.empty();
            }
            String repositoryPath = path.substring(
                    0,
                    path.length() - "/info/refs".length());
            return Optional.of(NativeHttpRequest.discovery(
                    initialRequestData(request, service, repositoryPath)));
        }
        if ("POST".equals(method) && path.endsWith("/git-upload-pack")) {
            String repositoryPath = path.substring(
                    0,
                    path.length() - "/git-upload-pack".length());
            return Optional.of(NativeHttpRequest.post(initialRequestData(
                    request,
                    InitialRequestService.UPLOAD_PACK,
                    repositoryPath)));
        }
        if ("POST".equals(method) && path.endsWith("/git-receive-pack")) {
            String repositoryPath = path.substring(
                    0,
                    path.length() - "/git-receive-pack".length());
            return Optional.of(NativeHttpRequest.post(initialRequestData(
                    request,
                    InitialRequestService.RECEIVE_PACK,
                    repositoryPath)));
        }
        return Optional.empty();
    }

    private static InitialRequestService serviceParameter(
            HttpServletRequest request) {
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

    private InitialRequestData initialRequestData(
            HttpServletRequest request,
            InitialRequestService service,
            String repositoryPath) {
        return new InitialRequestData(
                service,
                normalizeNativeRepositoryName(repositoryPath),
                requestHost(request),
                gitProtocolParameters(request.getHeader(GIT_PROTOCOL_HEADER)));
    }

    private static Map<String, String> gitProtocolParameters(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String token : value.split(":")) {
            int separator = token.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = token.substring(0, separator).trim();
            String parameterValue = token.substring(separator + 1).trim();
            if ("version".equals(name) && !parameterValue.isEmpty()) {
                parameters.put(name, parameterValue);
            }
        }
        return Map.copyOf(parameters);
    }

    private static String requestHost(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            return host;
        }
        return null;
    }

    private static void writeServiceAnnouncement(
            JettyBufferedByteOutput output,
            InitialRequestService service)
            throws IOException {
        GitBufferedByteTransportAdapter adapter =
                new GitBufferedByteTransportAdapter(
                        null,
                        output,
                        UnpooledByteBufAllocator.DEFAULT);
        adapter.writeTextLine("# service=" + service.wireName());
        adapter.writeFlush();
    }

    private static String advertisementContentType(
            InitialRequestService service) {
        return service == InitialRequestService.UPLOAD_PACK
                ? UPLOAD_ADVERTISEMENT_TYPE
                : RECEIVE_ADVERTISEMENT_TYPE;
    }

    private static String requestContentType(InitialRequestService service) {
        return service == InitialRequestService.UPLOAD_PACK
                ? UPLOAD_REQUEST_TYPE
                : RECEIVE_REQUEST_TYPE;
    }

    private static String resultContentType(InitialRequestService service) {
        return service == InitialRequestService.UPLOAD_PACK
                ? UPLOAD_RESULT_TYPE
                : RECEIVE_RESULT_TYPE;
    }

    private static boolean contentTypeMatches(
            String actual,
            String expected) {
        if (actual == null) {
            return false;
        }
        return actual.split(";", 2)[0]
                .trim()
                .equalsIgnoreCase(expected);
    }

    private static boolean causedByAccessDenied(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof GitNativeRepositoryAccessHook
                    .AccessDeniedException) {
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
            if (message != null
                    && message.contains("Native repository does not exist")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String normalizeNativeRepositoryName(
            String rawRepositoryName) {
        String repositoryName = rawRepositoryName == null ? "" : rawRepositoryName;
        while (repositoryName.startsWith("/")) {
            repositoryName = repositoryName.substring(1);
        }
        repositoryName = repositoryName.replaceFirst("\\.git$", "");
        if (repositoryName.isBlank()
                || repositoryName.contains("\0")
                || repositoryName.contains("\\")
                || repositoryName.contains("..")) {
            throw new IllegalArgumentException(
                    "Invalid Git repository path");
        }
        return repositoryName;
    }

    private record NativeHttpRequest(
            boolean discovery,
            InitialRequestData data) {

        private InitialRequestService service() {
            return data.getService();
        }

        private static NativeHttpRequest discovery(
                InitialRequestData data) {
            return new NativeHttpRequest(true, data);
        }

        private static NativeHttpRequest post(
                InitialRequestData data) {
            return new NativeHttpRequest(false, data);
        }
    }

    private static final class NativeHttpRepositoryAccessHook
            implements GitNativeRepositoryAccessHook {
        private final SecurityContext securityContext;

        private NativeHttpRepositoryAccessHook(
                SecurityContext securityContext) {
            this.securityContext = Objects.requireNonNull(
                    securityContext,
                    "securityContext");
        }

        @Override
        public void beforeReceive(String repositoryName) {
            require(() -> accessEnforcer().require(
                    securityContext,
                    SubjectAccessRules.authenticated()));
        }

        @Override
        public void beforeRead(String repositoryName) {
            RepositoryResource repositoryResource =
                    repositoryResource(repositoryName);
            require(() -> accessEnforcer().require(
                    securityContext,
                    SubjectAccessRules.authenticated()));
            require(() -> accessEnforcer().require(
                    securityContext,
                    repositoryResource,
                    RepositoryAccessRules.read()));
        }

        @Override
        public void beforeCreate(String repositoryName) {
            RepositoryResource repositoryResource =
                    repositoryResource(repositoryName);
            require(() -> accessEnforcer().require(
                    securityContext,
                    repositoryResource,
                    RepositoryAccessRules.create()));
        }

        @Override
        public void beforeWrite(String repositoryName) {
            RepositoryResource repositoryResource =
                    repositoryResource(repositoryName);
            require(() -> accessEnforcer().require(
                    securityContext,
                    repositoryResource,
                    RepositoryAccessRules.write()));
        }

        private static RepositoryResource repositoryResource(
                String repositoryName) {
            return RepositoryResource.of(
                    normalizeNativeRepositoryName(repositoryName));
        }

        private static void require(AccessCheck accessCheck) {
            try {
                accessCheck.require();
            } catch (OrionSecurityException error) {
                throw new GitNativeRepositoryAccessHook
                        .AccessDeniedException(
                                error.getMessage(),
                                error);
            }
        }
    }

    private interface AccessCheck {
        void require() throws OrionSecurityException;
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
