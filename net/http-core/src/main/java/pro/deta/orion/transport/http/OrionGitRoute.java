package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.GitFilter;
import org.eclipse.jgit.lib.Repository;
import pro.deta.orion.GitRepositoryProvider;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

public class OrionGitRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*";
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST");

    private final GitFilter gitFilter = new GitFilter();

    @Inject
    public OrionGitRoute(GitRepositoryProvider gitRepositoryProvider) {
        gitFilter.setRepositoryResolver((request, repositoryName) -> gitRepositoryProvider
                .findOrCreate(repositoryName)
                .valueOrFailure("Failed to open repository " + repositoryName)
                .unwrapOrThrow(Repository.class));
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
