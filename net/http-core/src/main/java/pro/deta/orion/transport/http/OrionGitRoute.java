package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.transport.git.auth.AuthenticatedRepositoryAccessHook;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.GIT;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.HEAD;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.POST;

public class OrionGitRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/**";
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
    private static final List<OrionHttpRouteDefinition.Method> ALLOWED_METHODS = List.of(GET, HEAD, POST);
    private static final Map<String, String> METHOD_REJECTION_HEADERS = Map.of(
            EXPIRES, NO_CACHE_EXPIRES,
            PRAGMA, "no-cache",
            CACHE_CONTROL, NO_CACHE);
    private static final OrionHttpRouteDefinition DEFINITION = new OrionHttpRouteDefinition(
            URL_PATTERN,
            GIT,
            ALLOWED_METHODS,
            METHOD_REJECTION_HEADERS);

    private final DefaultGitNativeRepositoryService repositoryService;
    private final GitTransportConfig gitTransportConfig;
    private final NativeGitRepositoryProvider repositoryProvider;
    private final OrionGitPackfileHandler packfiles;

    @Inject
    public OrionGitRoute(
            DefaultGitNativeRepositoryService repositoryService,
            GitTransportConfig gitTransportConfig,
            NativeGitRepositoryProvider repositoryProvider) {
        this.repositoryService = Objects.requireNonNull(
                repositoryService,
                "repositoryService");
        this.gitTransportConfig = Objects.requireNonNull(gitTransportConfig, "gitTransportConfig");
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.packfiles = new OrionGitPackfileHandler(repositoryProvider);
    }

    @Override
    public OrionHttpRouteDefinition definition() {
        return DEFINITION;
    }

    @Override
    public void service(OrionHttpExchange exchange) throws IOException {
        if (!GIT.allows(exchange.request())) {
            exchange.sendError(SC_FORBIDDEN);
            return;
        }
        handle(exchange);
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/r/")) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        int boundary = path.indexOf(".git/", 3);
        if (boundary < 0) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        String repositoryName;
        try {
            repositoryName = RepositoryName.parse(path.substring(3, boundary)).value();
        } catch (IllegalArgumentException failure) {
            throw new HttpRequestValidationException("Invalid repository name");
        }
        new RepositoryHandler(repositoryName).handle(exchange, path.substring(boundary + 5));
    }

    private final class RepositoryHandler {
        private final String repositoryName;

        private RepositoryHandler(String repositoryName) {
            this.repositoryName = repositoryName;
        }

        private void handle(OrionHttpExchange exchange, String path) throws IOException {
            if (path.startsWith("objects/pack/")) {
                packfiles.handle(exchange, repositoryName, path.substring("objects/pack/".length()));
                return;
            }
            boolean discovery;
            InitialRequestService service;
            switch (path) {
                case "info/refs" -> {
                    if (!exchange.accepts(new OrionHttpRouteDefinition(
                            path, GIT, List.of(GET, HEAD), METHOD_REJECTION_HEADERS))) {
                        return;
                    }
                    discovery = true;
                    service = serviceParameter(exchange.request());
                    if (service == null) {
                        exchange.sendError(SC_BAD_REQUEST);
                        return;
                    }
                }
                case "git-upload-pack", "git-receive-pack" -> {
                    if (!exchange.accepts(new OrionHttpRouteDefinition(
                            path, GIT, List.of(POST), METHOD_REJECTION_HEADERS))) {
                        return;
                    }
                    discovery = false;
                    service = path.equals("git-upload-pack")
                            ? InitialRequestService.UPLOAD_PACK : InitialRequestService.RECEIVE_PACK;
                }
                default -> {
                    exchange.sendError(SC_NOT_FOUND);
                    return;
                }
            }
            handleNative(exchange, new NativeHttpRequest(discovery, service, repositoryName));
        }
    }

    private void handleNative(OrionHttpExchange exchange, NativeHttpRequest request) throws IOException {
        try {
            AuthenticatedRepositoryAccessHook access =
                    new AuthenticatedRepositoryAccessHook(securityContextFrom(exchange.request()));
            String name = request.repositoryPath();
            if (!repositoryProvider.isPublicRepositoryName(name)) {
                exchange.sendError(SC_FORBIDDEN);
                return;
            }
            if (request.service() == InitialRequestService.RECEIVE_PACK) {
                access.beforeReceive(name);
                if (repositoryProvider.exists(name)) {
                    access.beforeWrite(name);
                } else {
                    access.beforeCreate(name);
                }
            } else {
                access.beforeRead(name);
            }
            if (request.discovery()) {
                handleNativeDiscovery(exchange, request);
            } else {
                handleNativePost(exchange, request);
            }
        } catch (UnsupportedContentEncodingException error) {
            exchange.sendError(SC_UNSUPPORTED_MEDIA_TYPE, METHOD_REJECTION_HEADERS);
        } catch (GitNativeRepositoryAccessHook.AccessDeniedException error) {
            exchange.sendError(SC_FORBIDDEN);
        } catch (IOException error) {
            if (causedByAccessDenied(error)) {
                exchange.sendError(SC_FORBIDDEN);
            } else if (causedByInvalidContentEncoding(error)) {
                exchange.sendError(SC_BAD_REQUEST, METHOD_REJECTION_HEADERS);
            } else if (missingRepository(error)) {
                exchange.sendError(SC_NOT_FOUND);
            } else {
                throw error;
            }
        }
    }

    private void handleNativeDiscovery(
            OrionHttpExchange exchange,
            NativeHttpRequest request) throws IOException {
        HttpServletRequest req = exchange.request();
        OrionHttpResponse metadata = noCache(
                OrionHttpResponse.stream(SC_OK, advertisementContentType(request.service())));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(req.getInputStream())) {
            OutputStreamBufferedByteOutput output =
                    new OutputStreamBufferedByteOutput(exchange.openResponseBody(metadata));
            GitWireBootstrap bootstrap = gitWireBootstrap(req, request, input, output);
            Optional<String> packfileUriBase = OrionGitPackfileUriBaseResolver.resolve(
                    req, gitTransportConfig.getPackfileUri());
            if (bootstrap.data()
                    .getProtocolVersion()
                    .filter(GitProtocolVersion.V2::equals)
                    .isEmpty()) {
                writeServiceAnnouncement(bootstrap.wire(), request.service());
            }
            SecurityContext securityContext = securityContextFrom(req);
            session(securityContext, packfileUriBase, bootstrap.wire()).advertise(bootstrap.data());
        }
    }

    private void handleNativePost(
            OrionHttpExchange exchange,
            NativeHttpRequest request) throws IOException {
        HttpServletRequest req = exchange.request();
        if (!contentTypeMatches(req.getContentType(), requestContentType(request.service()))) {
            exchange.sendError(SC_UNSUPPORTED_MEDIA_TYPE, METHOD_REJECTION_HEADERS);
            return;
        }
        OrionHttpResponse metadata = noCache(
                OrionHttpResponse.stream(SC_OK, resultContentType(request.service())));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(
                GitHttpRequestBody.decode(
                        req.getInputStream(),
                        req.getHeader("Content-Encoding")))) {
            OutputStreamBufferedByteOutput output =
                    new OutputStreamBufferedByteOutput(exchange.openResponseBody(metadata));
            GitWireBootstrap bootstrap = gitWireBootstrap(req, request, input, output);
            Optional<String> packfileUriBase = OrionGitPackfileUriBaseResolver.resolve(
                    req, gitTransportConfig.getPackfileUri());
            SecurityContext securityContext = securityContextFrom(req);
            session(securityContext, packfileUriBase, bootstrap.wire())
                    .serveSmartHttpPost(bootstrap.data());
        }
    }

    private static @NonNull GitWireBootstrap gitWireBootstrap(
            HttpServletRequest req,
            NativeHttpRequest request,
            BufferedByteInputV2 input,
            OutputStreamBufferedByteOutput output) {
        try {
            return GitWireBootstrap.smartHttp(
                    input,
                    output,
                    request.service(),
                    request.repositoryPath(),
                    requestHost(req),
                    req.getHeader(GIT_PROTOCOL_HEADER));
        } catch (IllegalArgumentException failure) {
            throw new HttpRequestValidationException("Invalid Git HTTP request");
        }
    }

    private GitBlockingWireSession session(
            SecurityContext securityContext,
            Optional<String> packfileUriBase,
            GitBlockingWireTransport wire) {
        return new GitBlockingWireSession(
                data -> repositoryService.open(data,
                        new AuthenticatedRepositoryAccessHook(securityContext), packfileUriBase),
                GitWireConfiguration.allSupported(),
                wire);
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
        return service == InitialRequestService.UPLOAD_PACK
                ? UPLOAD_ADVERTISEMENT_TYPE
                : RECEIVE_ADVERTISEMENT_TYPE;
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

    private static OrionHttpResponse noCache(OrionHttpResponse response) {
        OrionHttpResponse result = response;
        for (Map.Entry<String, String> header : METHOD_REJECTION_HEADERS.entrySet()) {
            result = result.withHeader(header.getKey(), header.getValue());
        }
        return result;
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

    private static boolean causedByInvalidContentEncoding(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InvalidContentEncodingException) {
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

    }

    private static SecurityContext securityContextFrom(HttpServletRequest req) {
        Object attribute = req.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (attribute instanceof SecurityContext securityContext) {
            return securityContext;
        }
        return SecurityContext.createContext().withRequestId(req.toString());
    }

}
