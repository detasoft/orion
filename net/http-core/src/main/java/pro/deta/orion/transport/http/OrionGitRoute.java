package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.GitFilter;
import org.eclipse.jgit.lib.Repository;
import pro.deta.orion.GitRepositoryProvider;

import java.io.IOException;
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
        gitFilter.doFilter(req, resp, (request, response) -> ((HttpServletResponse) response).sendError(SC_NOT_FOUND));
    }
}
