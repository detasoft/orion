package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class NotFoundServlet implements MapToUrlServlet {
    @Override
    public String servletPath() {
        return "";
    }

    @Override
    public void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setContentType("text/plain");
        servletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
        servletResponse.getWriter().println("NOT_FOUND");
    }
}
