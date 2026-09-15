package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.command.SessionCommandService;
import pro.deta.orion.agent.server.journal.JournalStorageException;
import pro.deta.orion.agent.server.registry.AgentRegistryException;
import pro.deta.orion.agent.server.registry.SessionRegistryException;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.POST;

/** Exposes input, resize, and existing command outcomes to application administrators. */
public final class SessionCommandsRoute extends BaseAdminRoute {
    private static final String PREFIX = OrionAdminPaths.SESSIONS + "/";
    private static final String SUFFIX = "/commands";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final AgentSessionServer server;
    private final ObjectMapper mapper;

    @Inject
    public SessionCommandsRoute(AgentSessionServer server, ObjectMapper mapper) {
        super(PREFIX + "*" + SUFFIX, GET, POST);
        this.server = server;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        SessionId sessionId;
        CommandId commandId;
        try {
            sessionId = sessionId(request);
            commandId = new CommandId(request.getParameter("commandId"));
        } catch (IllegalArgumentException | NullPointerException failure) {
            return OrionHttpResponse.text(400, "Invalid session or command ID");
        }
        try {
            SessionCommandService.Status status = server.commandService().status(commandId);
            if (!status.sessionId().equals(sessionId)) {
                return OrionHttpResponse.text(404, "Command not found in this session");
            }
            return statusResponse(status);
        } catch (IllegalArgumentException failure) {
            return OrionHttpResponse.text(404, "Command not found");
        } catch (IOException | JournalStorageException | IllegalStateException failure) {
            return OrionHttpResponse.text(503, "Command status unavailable");
        }
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.split(";", 2)[0].trim().equals("application/json")) {
            return OrionHttpResponse.text(415, "Expected application/json");
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return OrionHttpResponse.text(413, "Command is too large");
        }
        SessionId sessionId;
        CommandId commandId;
        String operation;
        ProtocolBytes bytes = null;
        int columns = 0;
        int rows = 0;
        try {
            sessionId = sessionId(request);
            JsonNode command = mapper.readTree(body);
            commandId = new CommandId(text(command, "commandId"));
            operation = text(command, "operation");
            switch (operation) {
                case "input" -> bytes = ProtocolBytes.copyOf(Base64.getDecoder().decode(text(command, "bytes")));
                case "resize" -> {
                    columns = dimension(command, "columns");
                    rows = dimension(command, "rows");
                }
                default -> throw new IllegalArgumentException("Unsupported terminal command");
            }
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            return OrionHttpResponse.text(400, "Invalid terminal command");
        }
        try {
            AgentLabel owner = server.sessionOwner(sessionId).orElse(null);
            if (owner == null) {
                return OrionHttpResponse.text(404, "Session not found");
            }
            SessionCommandService commands = server.commandService();
            return statusResponse(operation.equals("input")
                    ? commands.input(owner, commandId, sessionId, UUID.randomUUID(), bytes)
                    : commands.resize(owner, commandId, sessionId, columns, rows));
        } catch (IllegalArgumentException failure) {
            return OrionHttpResponse.text(409, failure.getMessage());
        } catch (AgentProtocolException failure) {
            return OrionHttpResponse.text(400, "Invalid terminal command");
        } catch (IOException | AgentRegistryException | SessionRegistryException
                 | JournalStorageException | IllegalStateException failure) {
            return OrionHttpResponse.text(503, "Command delivery unavailable");
        }
    }

    private static OrionHttpResponse statusResponse(SessionCommandService.Status status) {
        return OrionHttpResponse.ok(Map.of(
                "commandId", status.commandId().value(),
                "sessionId", status.sessionId().value(),
                "operationSequence", Long.toUnsignedString(status.operationSequence()),
                "phase", status.phase().name(),
                "outcome", status.outcome().map(Enum::name).orElse(""),
                "detail", status.detail())).withHeader("Cache-Control", "no-store");
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.path(field).isTextual()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value.path(field).textValue();
    }

    private static int dimension(JsonNode value, String field) {
        JsonNode size = value.path(field);
        if (!size.isIntegralNumber() || !size.canConvertToInt() || size.intValue() < 1 || size.intValue() > 65535) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return size.intValue();
    }

    private static SessionId sessionId(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        if (path == null || !path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("Invalid session command path");
        }
        return new SessionId(path.substring(PREFIX.length(), path.length() - SUFFIX.length()));
    }
}
