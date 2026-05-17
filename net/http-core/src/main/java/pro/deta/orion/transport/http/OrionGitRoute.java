package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitFilter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public class OrionGitRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*";
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST");

    private final GitFilter gitFilter = new GitFilter();

    @Inject
    public OrionGitRoute(GitRepositoryProvider gitRepositoryProvider) {
        gitFilter.setRepositoryResolver((request, repositoryName) -> openRepository(
                gitRepositoryProvider,
                request,
                repositoryName));
        gitFilter.setUploadPackFactory((request, repository) -> new UploadPack(repository));
        gitFilter.setReceivePackFactory((request, repository) -> new ReceivePack(repository));
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
