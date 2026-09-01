package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        if (metadataStart < 0) {
            throw new IllegalArgumentException("Malformed native Git request");
        }
        String command = value.substring(0, metadataStart).trim();
        int commandSeparator = firstWhitespace(command);
        if (commandSeparator <= 0) {
            throw new IllegalArgumentException("Malformed native Git request");
        }
        String service = command.substring(0, commandSeparator);
        String repositoryPath = command.substring(commandSeparator).trim();
        Map<String, String> metadata = metadata(value.substring(metadataStart + 1));
        return new InitialRequestData(InitialRequestService.fromWireName(service), normalizeRepositoryPath(repositoryPath), metadata.get("host"), versionParameter(metadata.get(VERSION_PARAMETER)));
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

    private static Map<String, String> metadata(String value) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String token : value.split("\0")) {
            int separator = token.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            metadata.put(token.substring(0, separator).trim(), token.substring(separator + 1).trim());
        }
        return Map.copyOf(metadata);
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
            if (VERSION_PARAMETER.equals(name) && !parameterValue.isEmpty()) {
                parameters.put(name, parameterValue);
            }
        }
        return Map.copyOf(parameters);
    }

    private static Map<String, String> versionParameter(String version) {
        if (version == null || version.isBlank()) {
            return Map.of();
        }
        return Map.of(VERSION_PARAMETER, version);
    }

    private static String normalizeRepositoryPath(String repository) {
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
}
