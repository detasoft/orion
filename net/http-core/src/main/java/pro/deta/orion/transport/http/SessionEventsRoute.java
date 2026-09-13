package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.journal.JournalReadResult;
import pro.deta.orion.agent.server.journal.JournalStorageException;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;

public final class SessionEventsRoute extends BaseAdminRoute {
    private static final String PREFIX = OrionAdminPaths.SESSIONS + "/";
    private static final String SUFFIX = "/events";
    private static final String CONTENT_TYPE = "application/cbor-seq";
    private final AgentSessionServer server;

    @Inject
    public SessionEventsRoute(AgentSessionServer server) {
        super(PREFIX + "*" + SUFFIX, GET);
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException {
        HttpServletRequest request = exchange.request();
        SessionId sessionId;
        Optional<EventId> after;
        try {
            sessionId = sessionId(routePath(request));
            after = after(request.getParameter("after"));
        } catch (IllegalArgumentException failure) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        JournalReadResult page;
        try {
            page = server.readSessionEvents(sessionId, after);
        } catch (JournalStorageException failure) {
            exchange.sendError(failure.reason() == JournalStorageException.Reason.CLOSED
                    ? SC_SERVICE_UNAVAILABLE : SC_INTERNAL_SERVER_ERROR);
            return;
        } catch (IllegalStateException failure) {
            exchange.sendError(SC_SERVICE_UNAVAILABLE);
            return;
        }
        OutputStream output = exchange.openResponseBody(OrionHttpResponse.stream(SC_OK, CONTENT_TYPE)
                .withHeader("Cache-Control", "no-store"));
        for (var record : page.records()) {
            output.write(record.encodedRecord().toByteArray());
        }
    }

    private static SessionId sessionId(String path) {
        if (path == null || !path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("Invalid session event path");
        }
        return new SessionId(path.substring(PREFIX.length(), path.length() - SUFFIX.length()));
    }

    private static Optional<EventId> after(String value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid session event cursor");
        }
        return Optional.of(EventId.fromUnsigned(new BigInteger(value)));
    }

    private static String routePath(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path != null && !path.isBlank() ? path : request.getRequestURI();
    }
}
