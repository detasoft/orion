package pro.deta.orion.transport.http;

import io.netty.buffer.UnpooledByteBufAllocator;
import jakarta.inject.Inject;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.NameRevCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitFilter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreUploadHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.BranchAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.schema.config.GitPackfileUriConfig;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriBuilder;
import pro.deta.orion.git.nativestorage.upload.PublishedPackfileUriSource;
import pro.deta.orion.git.parser.wire.GitByteBufTransportAdapter;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.pkt.GitBufferedByteTransportAdapter;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
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
    private static final String REPOSITORY_NAME_ATTRIBUTE = OrionGitRoute.class.getName() + ".repositoryName";
    private static final String BRANCH_REF_PREFIX = "refs/heads/";
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

    private final GitFilter gitFilter;
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
        this.gitFilter = null;
    }

    public OrionGitRoute(GitRepositoryProvider gitRepositoryProvider) {
        this.nativeRepositoryProvider = null;
        this.gitTransportConfig = null;
        this.gitFilter = new GitFilter();
        gitFilter.setRepositoryResolver((request, repositoryName) -> openRepository(
                gitRepositoryProvider,
                request,
                repositoryName));
        gitFilter.setUploadPackFactory(OrionGitRoute::uploadPackFor);
        gitFilter.setReceivePackFactory(OrionGitRoute::receivePackFor);
        try {
            gitFilter.init(new NoOpFilterConfig());
        } catch (ServletException e) {
            throw new IllegalStateException("Cannot initialize Git HTTP filter", e);
        }
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
        if (nativeRepositoryProvider != null) {
            handleNative(req, resp);
            return;
        }
        gitFilter.doFilter(
                gitRequest(req),
                resp,
                (request, response) ->
                        ((HttpServletResponse) response)
                                .sendError(SC_NOT_FOUND));
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
        adapter(req).advertise(request.data(), output);
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
                GitByteBufTransportAdapter.DEFAULT_INPUT_BUFFER_SIZE)) {
            adapter(req).serveSmartHttpPost(
                    request.data(),
                    input,
                    new JettyBufferedByteOutput(resp.getOutputStream()));
        }
    }

    private GitByteBufTransportAdapter adapter(HttpServletRequest request) {
        return new GitByteBufTransportAdapter(
                UnpooledByteBufAllocator.DEFAULT,
                nativeRepositoryProvider,
                new NativeHttpRepositoryAccessHook(securityContextFrom(request)),
                GitWireConfiguration.allSupported(),
                packfileUriSourceFactory(request));
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

    static Repository openRepository(
            GitRepositoryProvider gitRepositoryProvider,
            HttpServletRequest request,
            String rawRepositoryName)
            throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceMayNotContinueException {
        String repositoryName = normalizeRepositoryName(rawRepositoryName);
        rememberRepositoryName(request, repositoryName);
        GitOperation operation = operationFor(request);
        SecurityContext securityContext = securityContextFrom(request);
        RepositoryResource resource = RepositoryResource.of(repositoryName);
        try {
            accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
            boolean repositoryExists = gitRepositoryProvider.exists(repositoryName);
            if (operation == GitOperation.WRITE) {
                if (repositoryExists) {
                    accessEnforcer().require(securityContext, resource, RepositoryAccessRules.write());
                    return repositoryFrom(gitRepositoryProvider.find(repositoryName), repositoryName);
                }
                accessEnforcer().require(securityContext, resource, RepositoryAccessRules.create());
                return repositoryFrom(gitRepositoryProvider.findOrCreate(repositoryName), repositoryName);
            }
            if (!repositoryExists) {
                throw new RepositoryNotFoundException(repositoryName);
            }
            accessEnforcer().require(securityContext, resource, RepositoryAccessRules.read());
            return repositoryFrom(gitRepositoryProvider.find(repositoryName), repositoryName);
        } catch (OrionSecurityException e) {
            throw new ServiceNotAuthorizedException(e.getMessage());
        }
    }

    private static void rememberRepositoryName(HttpServletRequest request, String repositoryName) {
        try {
            request.setAttribute(REPOSITORY_NAME_ATTRIBUTE, repositoryName);
        } catch (UnsupportedOperationException ignored) {
            // Some unit-test request proxies intentionally implement only the servlet methods under test.
        }
    }

    private static UploadPack uploadPackFor(HttpServletRequest request, Repository repository)
            throws ServiceNotAuthorizedException {
        SecurityContext securityContext = securityContextFrom(request);
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryNameFrom(request));
        UploadPack uploadPack = new UploadPack(repository);
        uploadPack.setPreUploadHook(new PreUploadHook() {
            @Override
            public void onBeginNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntOffered)
                    throws ServiceMayNotContinueException {
                requireFetchAccess(securityContext, repositoryResource, up.getRepository(), wants);
            }

            @Override
            public void onEndNegotiateRound(
                    UploadPack up,
                    Collection<? extends ObjectId> wants,
                    int cntCommon,
                    int cntNotFound,
                    boolean ready) throws ServiceMayNotContinueException {
                requireFetchAccess(securityContext, repositoryResource, up.getRepository(), wants);
            }

            @Override
            public void onSendPack(UploadPack up, Collection<? extends ObjectId> wants, Collection<? extends ObjectId> haves)
                    throws ServiceMayNotContinueException {
                requireFetchAccess(securityContext, repositoryResource, up.getRepository(), wants);
            }
        });
        return uploadPack;
    }

    private static ReceivePack receivePackFor(HttpServletRequest request, Repository repository)
            throws ServiceNotAuthorizedException {
        SecurityContext securityContext = securityContextFrom(request);
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryNameFrom(request));
        ReceivePack receivePack = new ReceivePack(repository);
        receivePack.setPreReceiveHook((ignored, commands) ->
                rejectUnauthorizedReceiveCommands(securityContext, repositoryResource, commands));
        return receivePack;
    }

    private static String repositoryNameFrom(HttpServletRequest request) throws ServiceNotAuthorizedException {
        Object attribute = request.getAttribute(REPOSITORY_NAME_ATTRIBUTE);
        if (attribute instanceof String repositoryName && !repositoryName.isBlank()) {
            return repositoryName;
        }
        try {
            return normalizeRepositoryName(repositoryPathFrom(request));
        } catch (RepositoryNotFoundException e) {
            throw new ServiceNotAuthorizedException("Repository name is unavailable");
        }
    }

    private static String repositoryPathFrom(HttpServletRequest request) {
        String path = stripRoutePrefix(routePath(request));
        if (path == null) {
            return "";
        }
        if (path.endsWith("/git-upload-pack")) {
            path = path.substring(0, path.length() - "/git-upload-pack".length());
        }
        if (path.endsWith("/git-receive-pack")) {
            path = path.substring(0, path.length() - "/git-receive-pack".length());
        }
        int infoRefs = path.indexOf("/info/refs");
        if (infoRefs >= 0) {
            path = path.substring(0, infoRefs);
        }
        return path;
    }

    private static void requireFetchAccess(
            SecurityContext securityContext,
            RepositoryResource repositoryResource,
            Repository repository,
            Collection<? extends ObjectId> wants) throws ServiceMayNotContinueException {
        if (wants == null || wants.isEmpty()) {
            return;
        }
        Map<ObjectId, String> branchNames = resolveBranchNames(repository, wants);
        for (ObjectId want : wants) {
            String branchName = branchNames.get(want);
            if (branchName == null) {
                throw new ServiceMayNotContinueException("ACCESS_DENIED");
            }
            try {
                accessEnforcer().require(
                        securityContext,
                        BranchResource.of(repositoryResource, branchName),
                        BranchAccessRules.fetch());
            } catch (OrionSecurityException e) {
                throw new ServiceMayNotContinueException("ACCESS_DENIED", e);
            }
        }
    }

    private static Map<ObjectId, String> resolveBranchNames(
            Repository repository,
            Collection<? extends ObjectId> objectIds) throws ServiceMayNotContinueException {
        NameRevCommand nameRev = new Git(repository).nameRev().addPrefix("refs/heads");
        for (ObjectId objectId : objectIds) {
            try {
                nameRev.add(objectId);
            } catch (MissingObjectException e) {
                throw new ServiceMayNotContinueException("ACCESS_DENIED", e);
            }
        }

        Map<ObjectId, String> branchNames;
        try {
            branchNames = nameRev.call();
        } catch (GitAPIException e) {
            throw new ServiceMayNotContinueException("ACCESS_DENIED", e);
        }

        Map<ObjectId, String> result = new LinkedHashMap<>();
        for (Map.Entry<ObjectId, String> entry : branchNames.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void rejectUnauthorizedReceiveCommands(
            SecurityContext securityContext,
            RepositoryResource repositoryResource,
            Collection<ReceiveCommand> commands) {
        for (ReceiveCommand command : commands) {
            if (command.getResult() != ReceiveCommand.Result.NOT_ATTEMPTED) {
                continue;
            }
            String branchName = branchNameFrom(command.getRefName());
            if (branchName != null && !canPushBranch(securityContext, repositoryResource, branchName)) {
                reject(command);
                continue;
            }
            if (command.getType() == ReceiveCommand.Type.UPDATE_NONFASTFORWARD
                    && !canForceUpdate(securityContext, repositoryResource)) {
                reject(command);
            }
        }
    }

    private static String branchNameFrom(String refName) {
        if (refName == null || !refName.startsWith(BRANCH_REF_PREFIX)) {
            return null;
        }
        return refName.substring(BRANCH_REF_PREFIX.length());
    }

    private static boolean canPushBranch(
            SecurityContext securityContext,
            RepositoryResource repositoryResource,
            String branchName) {
        try {
            accessEnforcer().require(
                    securityContext,
                    BranchResource.of(repositoryResource, branchName),
                    BranchAccessRules.push());
            return true;
        } catch (OrionSecurityException e) {
            return false;
        }
    }

    private static boolean canForceUpdate(SecurityContext securityContext, RepositoryResource repositoryResource) {
        try {
            accessEnforcer().require(securityContext, repositoryResource, RepositoryAccessRules.force());
            return true;
        } catch (OrionSecurityException e) {
            return false;
        }
    }

    private static void reject(ReceiveCommand command) {
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "ACCESS_DENIED");
    }

    private static Repository repositoryFrom(Result<GitRepository> result, String repositoryName)
            throws RepositoryNotFoundException, ServiceMayNotContinueException {
        return switch (result) {
            case Result.Success<GitRepository>(var repository) -> repository.unwrapOrThrow(Repository.class);
            case Result.Failure<GitRepository>(var code, var message, var throwable) -> {
                if (code == Result.FailureCode.NOT_FOUND) {
                    throw new RepositoryNotFoundException(repositoryName);
                }
                throw new ServiceMayNotContinueException("Cannot open repository " + repositoryName, throwable);
            }
            default -> throw new ServiceMayNotContinueException("Cannot open repository " + repositoryName);
        };
    }

    private static SecurityContext securityContextFrom(HttpServletRequest req) {
        Object attribute = req.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (attribute instanceof SecurityContext securityContext) {
            return securityContext;
        }
        return SecurityContext.createContext().withRequestId(req.toString());
    }

    private static GitOperation operationFor(HttpServletRequest request) {
        String service = request.getParameter("service");
        if ("git-receive-pack".equals(service)) {
            return GitOperation.WRITE;
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo != null && pathInfo.endsWith("/git-receive-pack")) {
            return GitOperation.WRITE;
        }
        return GitOperation.READ;
    }

    private static String normalizeRepositoryName(String rawRepositoryName) throws RepositoryNotFoundException {
        String repositoryName = rawRepositoryName == null ? "" : rawRepositoryName;
        while (repositoryName.startsWith("/")) {
            repositoryName = repositoryName.substring(1);
        }
        repositoryName = repositoryName.replaceFirst("\\.git$", "");
        if (repositoryName.isBlank()) {
            throw new RepositoryNotFoundException(rawRepositoryName);
        }
        return repositoryName;
    }

    private static HttpServletRequest gitRequest(HttpServletRequest req) {
        String pathInfo = stripRoutePrefix(routePath(req));
        return new HttpServletRequestWrapper(req) {
            @Override
            public String getPathInfo() {
                return pathInfo;
            }

            @Override
            public String getServletPath() {
                return "";
            }

            @Override
            public String getRequestURI() {
                String contextPath = super.getContextPath();
                if (contextPath == null) {
                    contextPath = "";
                }
                return contextPath + pathInfo;
            }
        };
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

    private enum GitOperation {
        READ,
        WRITE
    }

    private static final class NoOpFilterConfig implements FilterConfig {
        @Override
        public String getFilterName() {
            return OrionGitRoute.class.getSimpleName();
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }
}
