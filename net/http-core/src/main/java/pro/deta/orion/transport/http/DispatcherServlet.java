package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Slf4j
public class DispatcherServlet implements MapToUrlServlet {
    private final Map<String, OrionServlet> urlServletMap = new ConcurrentHashMap<>();
    private final String servletPath;
    private final NotFoundServlet notFoundServlet = new NotFoundServlet();

    @Inject
    public DispatcherServlet() {
        this("/");
    }

    public DispatcherServlet(String servletPath) {
        this.servletPath = servletPath;
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        log.debug("ACCESS_LOG: {}", req.getPathInfo());
        OrionServlet s = findServletMatch(req.getPathInfo());
        if (s != null)
            s.service(req, resp);
        else
            notFoundServlet.service(req,resp);
    }

    private OrionServlet findServletMatch(String url) {
        // First try exact match
        OrionServlet servlet = urlServletMap.get(url);
        if (servlet != null) {
            return servlet;
        }

        // If no exact match, try wildcard patterns
        for (Map.Entry<String, OrionServlet> entry : urlServletMap.entrySet()) {
            if (WildcardMatcher.matches(entry.getKey(), url)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public void addServlet(String url, OrionServlet servlet) {
        urlServletMap.put(url, servlet);
    }

    @Override
    public String servletPath() {
        return servletPath;
    }

    public <T extends Servlet & MapToUrlServlet> void register(T servlet) {
        addServlet(servlet.servletPath(), servlet);
    }
}
