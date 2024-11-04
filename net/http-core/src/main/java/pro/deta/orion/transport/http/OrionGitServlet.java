package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.GitFilter;
import pro.deta.orion.GitRepositoryProvider;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Singleton
public class OrionGitServlet implements MapToUrlServlet {
    private final GitFilter gitFilter = new GitFilter();

    @Inject
    public OrionGitServlet(GitRepositoryProvider gitRepositoryProvider) {
        gitFilter.setRepositoryResolver(gitRepositoryProvider.createResolver());
    }

    @Override
    public String servletPath() {
        return "/r/*";
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        gitFilter.doFilter(req, resp, (request, response) -> ((HttpServletResponse) response).sendError(SC_NOT_FOUND));
    }
}
