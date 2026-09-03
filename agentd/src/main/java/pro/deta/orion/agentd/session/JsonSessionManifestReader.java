package pro.deta.orion.agentd.session;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public final class JsonSessionManifestReader implements SessionManifestReader {
    private static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ARRAY_ITEMS = 4096;
    private static final int MAX_TEXT_LENGTH = 64 * 1024;
    private static final int SUPPORTED_METADATA_VERSION = 1;
    private static final int SUPPORTED_JOURNAL_VERSION = 1;
    private static final int SUPPORTED_CONTROL_VERSION = 1;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final MetadataInputOpener inputOpener;

    public JsonSessionManifestReader() {
        this(path -> Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS));
    }

    JsonSessionManifestReader(MetadataInputOpener inputOpener) {
        this.inputOpener = Objects.requireNonNull(inputOpener, "inputOpener");
    }

    @Override
    public SessionManifest read(Path sessionDirectory) throws IOException {
        Path directory = sessionDirectory.toAbsolutePath().normalize();
        Path metadata = directory.resolve("metadata");
        BasicFileAttributes attributes = Files.readAttributes(
                metadata, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() <= 0
                || attributes.size() > MAX_MANIFEST_BYTES) {
            throw invalid("metadata must be a bounded regular file");
        }

        byte[] bytes;
        try (InputStream input = inputOpener.open(metadata)) {
            bytes = input.readNBytes((int) MAX_MANIFEST_BYTES + 1);
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw invalid("metadata exceeds the maximum size");
        }

        Values values;
        try (JsonParser parser = JSON.createParser(bytes)) {
            values = readRoot(parser);
            if (parser.nextToken() != null) {
                throw invalid("metadata has trailing JSON content");
            }
        }
        return values.toManifest(directory);
    }

    private static Values readRoot(JsonParser parser) throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_OBJECT, "metadata must be a JSON object");
        Values values = new Values();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME, "metadata field name is missing");
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if (value == null) {
                throw invalid("metadata field " + name + " has no value");
            }
            switch (name) {
                case "metadataVersion" -> values.metadataVersion = readInt(parser, name);
                case "journalFormatVersion" -> values.journalVersion = readInt(parser, name);
                case "controlProtocolVersion" -> values.controlVersion = readInt(parser, name);
                case "sessionId" -> values.sessionId = readText(parser, name, false);
                case "createdAtEpochMillis" -> values.createdAt = readNonNegativeLong(parser, name);
                case "sessionStartEpochMillis" -> values.sessionStart = readNonNegativeLong(parser, name);
                case "command" -> values.command = readTextArray(parser, name, false);
                case "cwd" -> values.workingDirectory = readText(parser, name, false);
                case "hostPid" -> values.hostPid = readPositiveLong(parser, name);
                case "childPid" -> values.childPid = readOptionalPositiveLong(parser, name);
                case "initialCols" -> values.initialColumns = readDimension(parser, name);
                case "initialRows" -> values.initialRows = readDimension(parser, name);
                case "currentCols" -> values.currentColumns = readDimension(parser, name);
                case "currentRows" -> values.currentRows = readDimension(parser, name);
                case "term" -> values.terminalType = readTerminalType(parser, name);
                case "sandbox" -> values.sandbox = readSandbox(parser);
                case "control" -> values.control = readControl(parser);
                default -> parser.skipChildren();
            }
        }
        return values;
    }

    private static SessionManifest.Sandbox readSandbox(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "sandbox must be an object");
        Boolean requested = null;
        String enforcement = null;
        String unavailablePolicy = null;
        List<String> readWritePaths = null;
        List<String> readOnlyPaths = null;
        OptionalLong policyVersion = OptionalLong.empty();
        OptionalLong handledRights = OptionalLong.empty();
        List<SessionManifest.SandboxRule> rules = List.of();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME, "sandbox field name is missing");
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "requested" -> requested = readBoolean(parser, name);
                case "enforcement" -> enforcement = readText(parser, name, false);
                case "unavailablePolicy" -> unavailablePolicy = readText(parser, name, false);
                case "readWritePaths" -> readWritePaths = readTextArray(parser, name, true);
                case "readOnlyPaths" -> readOnlyPaths = readTextArray(parser, name, true);
                case "policyVersion" -> policyVersion = OptionalLong.of(readPositiveLong(parser, name));
                case "handledRights" -> handledRights = OptionalLong.of(readPositiveLong(parser, name));
                case "rules" -> rules = readSandboxRules(parser);
                default -> parser.skipChildren();
            }
        }
        if (requested == null || enforcement == null || unavailablePolicy == null
                || readWritePaths == null || readOnlyPaths == null) {
            throw invalid("sandbox is missing a required field");
        }
        return new SessionManifest.Sandbox(
                requested, enforcement, unavailablePolicy, readWritePaths, readOnlyPaths,
                policyVersion, handledRights, rules);
    }

    private static List<SessionManifest.SandboxRule> readSandboxRules(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_ARRAY, "rules must be an array");
        List<SessionManifest.SandboxRule> rules = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (rules.size() == MAX_ARRAY_ITEMS) {
                throw invalid("rules has too many items");
            }
            requireToken(parser.currentToken(), JsonToken.START_OBJECT, "sandbox rule must be an object");
            String path = null;
            List<String> rights = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(parser.currentToken(), JsonToken.FIELD_NAME, "sandbox rule field is missing");
                String name = parser.currentName();
                parser.nextToken();
                switch (name) {
                    case "path" -> path = readText(parser, name, false);
                    case "rights" -> rights = readTextArray(parser, name, false);
                    default -> parser.skipChildren();
                }
            }
            if (path == null || rights == null) {
                throw invalid("sandbox rule is missing a required field");
            }
            rules.add(new SessionManifest.SandboxRule(path, rights));
        }
        return List.copyOf(rules);
    }

    private static RawControl readControl(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "control must be an object");
        String transport = null;
        String endpoint = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME, "control field name is missing");
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "transport" -> transport = readText(parser, name, false);
                case "endpoint" -> endpoint = readText(parser, name, false);
                default -> parser.skipChildren();
            }
        }
        if (transport == null || endpoint == null) {
            throw invalid("control is missing a required field");
        }
        return new RawControl(transport, endpoint);
    }

    private static List<String> readTextArray(JsonParser parser, String name, boolean allowEmpty)
            throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_ARRAY, name + " must be a string array");
        List<String> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (values.size() == MAX_ARRAY_ITEMS) {
                throw invalid(name + " has too many items");
            }
            values.add(readText(parser, name, true));
        }
        if (!allowEmpty && values.isEmpty()) {
            throw invalid(name + " must not be empty");
        }
        return values;
    }

    private static String readText(JsonParser parser, String name, boolean allowEmpty) throws IOException {
        requireToken(parser.currentToken(), JsonToken.VALUE_STRING, name + " must be a string");
        String value = parser.getText();
        if ((!allowEmpty && value.isBlank()) || value.length() > MAX_TEXT_LENGTH) {
            throw invalid(name + " has an invalid length");
        }
        return value;
    }

    private static boolean readBoolean(JsonParser parser, String name) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (parser.currentToken() == JsonToken.VALUE_FALSE) {
            return false;
        }
        throw invalid(name + " must be a boolean");
    }

    private static int readInt(JsonParser parser, String name) throws IOException {
        requireInteger(parser, name);
        try {
            return parser.getIntValue();
        } catch (IOException error) {
            throw invalid(name + " is outside the supported integer range", error);
        }
    }

    private static int readPositiveInt(JsonParser parser, String name) throws IOException {
        int value = readInt(parser, name);
        if (value <= 0) {
            throw invalid(name + " must be positive");
        }
        return value;
    }

    private static int readDimension(JsonParser parser, String name) throws IOException {
        int value = readPositiveInt(parser, name);
        if (value > 65_535) {
            throw invalid(name + " exceeds the 16-bit manifest range");
        }
        return value;
    }

    private static String readTerminalType(JsonParser parser, String name) throws IOException {
        String value = readText(parser, name, false);
        if (value.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw invalid(name + " exceeds the manifest limit");
        }
        return value;
    }

    private static long readNonNegativeLong(JsonParser parser, String name) throws IOException {
        requireInteger(parser, name);
        long value;
        try {
            value = parser.getLongValue();
        } catch (IOException error) {
            throw invalid(name + " is outside the supported integer range", error);
        }
        if (value < 0) {
            throw invalid(name + " must be non-negative");
        }
        return value;
    }

    private static long readPositiveLong(JsonParser parser, String name) throws IOException {
        long value = readNonNegativeLong(parser, name);
        if (value == 0) {
            throw invalid(name + " must be positive");
        }
        return value;
    }

    private static OptionalLong readOptionalPositiveLong(JsonParser parser, String name) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(readPositiveLong(parser, name));
    }

    private static void requireInteger(JsonParser parser, String name) throws IOException {
        requireToken(parser.currentToken(), JsonToken.VALUE_NUMBER_INT, name + " must be an integer");
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String detail) throws IOException {
        if (actual != expected) {
            throw invalid(detail);
        }
    }

    private static void validateSessionId(String sessionId) throws IOException {
        if (sessionId.length() > 128 || sessionId.equals(".") || sessionId.equals("..")) {
            throw invalid("sessionId is invalid");
        }
        for (int index = 0; index < sessionId.length(); index++) {
            char character = sessionId.charAt(index);
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '_' || character == '-';
            if (!valid) {
                throw invalid("sessionId is invalid");
            }
        }
    }

    private static IOException invalid(String detail) {
        return new IOException("Invalid session metadata: " + detail);
    }

    private static IOException invalid(String detail, IOException cause) {
        return new IOException("Invalid session metadata: " + detail, cause);
    }

    @FunctionalInterface
    interface MetadataInputOpener {
        InputStream open(Path path) throws IOException;
    }

    private record RawControl(String transport, String endpoint) {
        ControlEndpoint resolve(Path directory) throws IOException {
            if (endpoint.length() > 4096) {
                throw invalid("control endpoint is too long");
            }
            return switch (transport) {
                case "unix-domain-socket" -> unixEndpoint(directory);
                case "named-pipe" -> new ControlEndpoint(
                        ControlEndpoint.Transport.NAMED_PIPE, endpoint, Path.of(endpoint));
                default -> throw invalid("unsupported control transport");
            };
        }

        private ControlEndpoint unixEndpoint(Path directory) throws IOException {
            Path declared = Path.of(endpoint);
            if (declared.isAbsolute()) {
                throw invalid("Unix control endpoint must be relative to the session directory");
            }
            Path address = directory.resolve(declared).normalize();
            if (!address.startsWith(directory) || address.equals(directory)) {
                throw invalid("Unix control endpoint escapes the session directory");
            }
            return new ControlEndpoint(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET, endpoint, address);
        }
    }

    private static final class Values {
        private Integer metadataVersion;
        private Integer journalVersion;
        private Integer controlVersion;
        private String sessionId;
        private Long createdAt;
        private Long sessionStart;
        private List<String> command;
        private String workingDirectory;
        private Long hostPid;
        private OptionalLong childPid;
        private Integer initialColumns;
        private Integer initialRows;
        private Integer currentColumns;
        private Integer currentRows;
        private String terminalType;
        private SessionManifest.Sandbox sandbox;
        private RawControl control;

        private SessionManifest toManifest(Path directory) throws IOException {
            requireFields();
            if (metadataVersion != SUPPORTED_METADATA_VERSION
                    || journalVersion != SUPPORTED_JOURNAL_VERSION
                    || controlVersion != SUPPORTED_CONTROL_VERSION) {
                throw invalid("metadata declares an unsupported format version");
            }
            validateSessionId(sessionId);
            if (!sessionId.equals(directory.getFileName().toString())) {
                throw invalid("metadata sessionId does not match its directory");
            }
            if (sessionStart < createdAt) {
                throw invalid("sessionStartEpochMillis precedes createdAtEpochMillis");
            }
            return new SessionManifest(
                    metadataVersion, journalVersion, controlVersion, sessionId, createdAt, sessionStart,
                    command, workingDirectory, hostPid, childPid, initialColumns, initialRows,
                    currentColumns, currentRows, terminalType, sandbox, control.resolve(directory));
        }

        private void requireFields() throws IOException {
            if (metadataVersion == null || journalVersion == null || controlVersion == null
                    || sessionId == null || createdAt == null || sessionStart == null || command == null
                    || workingDirectory == null || hostPid == null || childPid == null
                    || initialColumns == null || initialRows == null || currentColumns == null
                    || currentRows == null || terminalType == null || sandbox == null || control == null) {
                throw invalid("metadata is missing a required field");
            }
        }
    }
}
