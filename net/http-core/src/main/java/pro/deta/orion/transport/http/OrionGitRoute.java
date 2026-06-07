package pro.deta.orion.transport.http;

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
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public class OrionGitRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*";
    private static final String REPOSITORY_NAME_ATTRIBUTE = OrionGitRoute.class.getName() + ".repositoryName";
    private static final String BRANCH_REF_PREFIX = "refs/heads/";
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST");

    private final GitFilter gitFilter = new GitFilter();

    @Inject
    public OrionGitRoute(GitRepositoryProvider gitRepositoryProvider) {
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
    public void handle(HttpServletRequest req, HttpServletResponse resp, OrionHttpResponseWriter responseWriter) throws IOException, ServletException {
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            resp.setHeader("Allow", String.join(", ", ALLOWED_METHODS));
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        gitFilter.doFilter(gitRequest(req), resp, (request, response) -> ((HttpServletResponse) response).sendError(SC_NOT_FOUND));
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
