package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class GitWireBootstrap {
    private static final String VERSION_PARAMETER = "version";

    private final GitBlockingWireTransport wire;
    private final InitialRequestData data;

    private GitWireBootstrap(GitBlockingWireTransport wire, InitialRequestData data) {
        this.wire = Objects.requireNonNull(wire, "wire");
        this.data = Objects.requireNonNull(data, "data");
    }

    public static GitWireBootstrap smartHttp(BufferedByteInput input, BufferedByteOutput output, InitialRequestService service, String repositoryPath, String host, String gitProtocol) {
        return new GitWireBootstrap(new GitBlockingWireTransport(input, output), transportRequest(service, repositoryPath, host, gitProtocol));
    }

    public static GitWireBootstrap sshCommand(BufferedByteInput input, BufferedByteOutput output, String commandLine, String gitProtocol) {
        return new GitWireBootstrap(new GitBlockingWireTransport(input, output), sshCommandData(commandLine, gitProtocol));
    }

    public static InitialRequestData sshCommandData(String commandLine, String gitProtocol) {
        GitSshRequest request = parseGitSshCommand(commandLine);
        return transportRequest(request.service(), request.repositoryPath(), null, gitProtocol);
    }

    public static GitWireBootstrap nativeDaemon(BufferedByteInput input, BufferedByteOutput output) throws IOException {
        GitBlockingWireTransport wire = new GitBlockingWireTransport(input, output);
        GitBlockingWireTransport.GitPktLine packet = wire.readPacket();
        try {
            if (packet.control().type() != ControlState.ControlType.DATA) {
                throw new IllegalArgumentException("Malformed native Git request");
            }
            return new GitWireBootstrap(wire, nativeDaemonData(packet.payload().toString(StandardCharsets.UTF_8)));
        } finally {
            packet.payload().release();
        }
    }

    public GitBlockingWireTransport wire() {
        return wire;
    }

    public InitialRequestData data() {
        return data;
    }

    private static InitialRequestData transportRequest(InitialRequestService service, String repositoryPath, String host, String gitProtocol) {
        return new InitialRequestData(service, normalizeRepositoryPath(repositoryPath), host, gitProtocolParameters(gitProtocol));
    }

    private static InitialRequestData nativeDaemonData(String request) {
        String value = request == null ? "" : request;
        int metadataStart = value.indexOf('\0');
        String command = metadataStart < 0
                ? value
                : value.substring(0, metadataStart);
        if (command.endsWith("\n")) {
            command = command.substring(0, command.length() - 1);
        }
        command = command.trim();
        int commandSeparator = firstWhitespace(command);
        if (commandSeparator <= 0) {
            throw malformedNativeRequest();
        }
        String service = command.substring(0, commandSeparator);
        String repositoryPath = command.substring(commandSeparator).trim();
        NativeDaemonMetadata metadata = metadataStart < 0
                ? NativeDaemonMetadata.EMPTY
                : nativeDaemonMetadata(value.substring(metadataStart + 1));
        List<String> protocolParameters = metadata.protocolParameters();
        return new InitialRequestData(
                InitialRequestService.fromWireName(service),
                normalizeRepositoryPath(repositoryPath),
                metadata.host(),
                gitProtocolParameters(String.join(":", protocolParameters)),
                protocolParameters);
    }

    private static NativeDaemonMetadata nativeDaemonMetadata(String value) {
        int firstEnd = value.indexOf('\0');
        String firstArgument = firstEnd < 0
                ? value
                : value.substring(0, firstEnd);
        String host = null;
        int protocolStart = 0;
        if (!firstArgument.isEmpty()) {
            if (!firstArgument.regionMatches(true, 0, "host=", 0, 5)) {
                throw malformedNativeRequest();
            }
            host = canonicalHost(firstArgument.substring(5));
            protocolStart = firstEnd < 0 ? value.length() : firstEnd + 1;
            if (protocolStart < value.length()
                    && value.charAt(protocolStart) != '\0') {
                throw malformedNativeRequest();
            }
        }
        return new NativeDaemonMetadata(
                host,
                nativeDaemonProtocolParameters(value, protocolStart));
    }

    private static List<String> nativeDaemonProtocolParameters(
            String value,
            int start) {
        List<String> parameters = new ArrayList<>();
        int tokenStart = start;
        while (tokenStart < value.length()) {
            int tokenEnd = value.indexOf('\0', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = value.length();
            }
            if (tokenEnd > tokenStart) {
                parameters.add(value.substring(tokenStart, tokenEnd));
            }
            tokenStart = tokenEnd + 1;
        }
        return List.copyOf(parameters);
    }

    private static String canonicalHost(String value) {
        String host = hostWithoutPort(value);
        StringBuilder sanitized = new StringBuilder(host.length());
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (character == '/' || character == '\\') {
                continue;
            }
            if (character == '.'
                    && (sanitized.isEmpty()
                    || sanitized.charAt(sanitized.length() - 1) == '.')) {
                continue;
            }
            sanitized.append(character);
        }
        while (!sanitized.isEmpty()
                && sanitized.charAt(sanitized.length() - 1) == '.') {
            sanitized.setLength(sanitized.length() - 1);
        }
        return sanitized.toString().toLowerCase(Locale.ROOT);
    }

    private static String hostWithoutPort(String value) {
        if (!value.startsWith("[")) {
            int portSeparator = value.lastIndexOf(':');
            return portSeparator < 0
                    ? value
                    : value.substring(0, portSeparator);
        }
        int bracket = value.indexOf(']');
        if (bracket < 0
                || bracket + 1 < value.length()
                && value.charAt(bracket + 1) != ':') {
            throw malformedNativeRequest();
        }
        return value.substring(1, bracket);
    }

    private static IllegalArgumentException malformedNativeRequest() {
        return new IllegalArgumentException("Malformed native Git request");
    }

    private static GitSshRequest parseGitSshCommand(String commandLine) {
        String value = commandLine == null ? "" : commandLine.trim();
        int firstSeparator = firstWhitespace(value);
        if (firstSeparator <= 0) {
            throw new IllegalArgumentException("Malformed Git SSH command: " + commandLine);
        }
        String serviceName = value.substring(0, firstSeparator);
        String repository = parseRepositoryArgument(value.substring(firstSeparator).trim());
        return new GitSshRequest(InitialRequestService.fromWireName(serviceName), repository);
    }

    private static String parseRepositoryArgument(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Malformed Git SSH command: missing repository");
        }
        if (value.charAt(0) != '\'') {
            String[] parts = value.split("\\s+", -1);
            if (parts.length != 1) {
                throw new IllegalArgumentException("Malformed Git SSH command: too many arguments");
            }
            return parts[0];
        }
        int closingQuote = value.indexOf('\'', 1);
        if (closingQuote < 0 || !value.substring(closingQuote + 1).trim().isEmpty()) {
            throw new IllegalArgumentException("Malformed Git SSH command: invalid repository quoting");
        }
        return value.substring(1, closingQuote);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static Map<String, String> gitProtocolParameters(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String token : value.split(":")) {
            int separator = token.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = token.substring(0, separator).trim();
            String parameterValue = token.substring(separator + 1).trim();
            if (VERSION_PARAMETER.equals(name)) {
                acceptProtocolVersion(parameters, parameterValue);
            }
        }
        return Map.copyOf(parameters);
    }

    private static void acceptProtocolVersion(
            Map<String, String> parameters,
            String candidate) {
        int candidateRank = protocolVersionRank(candidate);
        if (candidateRank < 0) {
            return;
        }
        String current = parameters.get(VERSION_PARAMETER);
        if (current == null || candidateRank > protocolVersionRank(current)) {
            parameters.put(VERSION_PARAMETER, candidate);
        }
    }

    private static int protocolVersionRank(String value) {
        return switch (value) {
            case "0" -> 0;
            case "1" -> 1;
            case "2" -> 2;
            default -> -1;
        };
    }

    public static String normalizeRepositoryPath(String repository) {
        String normalized = repository == null ? "" : repository.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceFirst("\\.git$", "");
        if (normalized.isBlank() || normalized.contains("\0") || normalized.contains("\\") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid Git repository path");
        }
        return normalized;
    }

    private record GitSshRequest(InitialRequestService service, String repositoryPath) {
    }

    private record NativeDaemonMetadata(
            String host,
            List<String> protocolParameters) {
        private static final NativeDaemonMetadata EMPTY =
                new NativeDaemonMetadata(null, List.of());
    }
}
