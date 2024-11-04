package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RequiredArgsConstructor
public class ChallengeAuthorizationServlet implements MapToUrlServlet {
    private final String token;
    private final String authorization;
    private final CountDownLatch countDownLatch = new CountDownLatch(1);

    @Override
    public void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setContentType("text/plain");
        servletResponse.setStatus(HttpServletResponse.SC_OK);
        servletResponse.getWriter().println(authorization);
        countDownLatch.countDown();
    }

    @Override
    public String servletPath() {
        return "/.well-known/acme-challenge/" + token;
    }

    public CountDownLatch getLatch() {
        return countDownLatch;
    }
}
