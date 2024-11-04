package pro.deta.orion.transport.http;

public interface MapToUrlServlet extends OrionServlet {
    String servletPath();

    default String relativise(String s) {
        String servletPath = servletPath();
        if (servletPath.endsWith("/") && s.startsWith("/"))
            return servletPath + s.substring(1);
        return servletPath + s;
    }

    default String relativise() {
        return servletPath();
    }
}
