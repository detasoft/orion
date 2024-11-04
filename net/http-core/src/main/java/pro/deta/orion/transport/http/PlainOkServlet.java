package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class PlainOkServlet implements MapToUrlServlet {
    private final String path;

    public PlainOkServlet() {
        this("/ok");
    }

    public PlainOkServlet(String path) {
        this.path = path;
    }

    @Override
    public void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setContentType("text/plain");
        servletResponse.setStatus(HttpServletResponse.SC_OK);
        servletResponse.getWriter().println("OK");
    }

    @Override
    public String getServletInfo() {
        return "Respond OK with test/plain";
    }

    @Override
    public String servletPath() {
        return path;
    }
}
