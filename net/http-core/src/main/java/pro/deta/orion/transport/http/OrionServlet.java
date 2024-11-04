package pro.deta.orion.transport.http;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface OrionServlet extends Servlet {
    @Override
    default void init(ServletConfig servletConfig) throws ServletException {
    }

    @Override
    default ServletConfig getServletConfig() {
        return null;
    }

    @Override
    default void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
        if (servletRequest instanceof HttpServletRequest req && servletResponse instanceof HttpServletResponse resp)
            service(req,resp);
        else throw new IllegalStateException("Not a http servlet.");
    }

    void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException;

    @Override
    default String getServletInfo() {
        return "";
    }

    @Override
    default void destroy() {
    }
}
