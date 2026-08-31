package pro.deta.orion.transport.git.ssh;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.schema.config.GitPackfileUriConfig;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriBuilder;
import pro.deta.orion.git.nativestorage.upload.PublishedPackfileUriSource;
import pro.deta.orion.git.parser.wire.GitByteBufTransportAdapter;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.pkt.GitBufferedByteTransportAdapter;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.transport.git.auth.AuthenticatedNativeRepositoryAccessHook;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.stream.*;

import io.netty.buffer.UnpooledByteBufAllocator;
import jakarta.inject.Named;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Slf4j
public class SshCommandFactory implements CommandFactory {
    public static final String SET_KEY = "set-key";
    public static final String SHUTDOWN = "shutdown";
    public static final String ISSUE_TOKEN = "issue-token";
    public static final String TOKEN = "token";
    public static final String STATE = "state";
    public static final String STATUS = "status";
    private final OrionExecutor orionExecutor;
    private final OrionProvider orionProvider;
    private final OrionAccessControlService accessControlService;
    private final AggregateStateMachine runtimeStateMachine;
    private final NativeGitRepositoryProvider nativeRepositoryProvider;
    private final GitTransportConfig gitTransportConfig;
    private final long setKeyReadTimeoutMillis;

    @Inject
    public SshCommandFactory(
            OrionExecutor orionExecutor,
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            @Named("runtime") AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider nativeRepositoryProvider,
            GitTransportConfig gitTransportConfig) {
        this(orionExecutor, orionProvider, accessControlService,
                runtimeStateMachine, 30_000, nativeRepositoryProvider,
                gitTransportConfig);
    }

    public SshCommandFactory(
            OrionExecutor orionExecutor,
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine) {
        this(
                orionExecutor,
                orionProvider,
                accessControlService,
                runtimeStateMachine,
                30_000);
    }

    SshCommandFactory(
            OrionExecutor orionExecutor,
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine,
            long setKeyReadTimeoutMillis) {
        this(
                orionExecutor,
                orionProvider,
                accessControlService,
                runtimeStateMachine,
                setKeyReadTimeoutMillis,
                null,
                null);
    }

    SshCommandFactory(
            OrionExecutor orionExecutor,
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine,
            long setKeyReadTimeoutMillis,
            NativeGitRepositoryProvider nativeRepositoryProvider,
            GitTransportConfig gitTransportConfig) {
        this.orionExecutor = orionExecutor;
        this.orionProvider = orionProvider;
        this.accessControlService = accessControlService;
        this.runtimeStateMachine = runtimeStateMachine;
        this.nativeRepositoryProvider = nativeRepositoryProvider;
        this.gitTransportConfig = gitTransportConfig;
        this.setKeyReadTimeoutMillis = setKeyReadTimeoutMillis;
    }

    @Override
    public Command createCommand(ChannelSession channelSession, String commandLine) throws IOException {
        if (commandLine.startsWith("git-"))
            return new GitSshCommand(commandLine);
        else {
            return new OtherSshCommand(commandLine);
        }
    }

    @RequiredArgsConstructor
    private class OtherSshCommand extends CloseOnDestroyCommand {
        private final String commandLine;

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            try {
                orionExecutor.submit(() -> {
                    int returnCode = 0;
                    SecurityContext securityContext = securityContextFor(channel);
                    try {
                        accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());

                        List<String> arguments = commandArguments(commandLine);
                        String command = arguments.getFirst();

                        if (SET_KEY.equalsIgnoreCase(command)) {
                            try {
                                String username = channel.getSession().getUsername();
                                String publicKey = readKey(inputStream);
                                orionExecutor.submit(() -> accessControlService.addKeyToUser(username, publicKey));
                                outputStream.write(("Public: " + publicKey + " added successfully as authentication method for user " + username).getBytes(StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else if (SHUTDOWN.equalsIgnoreCase(command)) {
                            accessEnforcer().require(
                                    securityContext,
                                    ApplicationShutdownResource.applicationShutdown(),
                                    ApplicationAccessRules.shutdown());
                            orionExecutor.submit(() -> orionProvider.getOrionApplicationLifecycle().beginShutdown());
                        } else if (ISSUE_TOKEN.equalsIgnoreCase(command) || TOKEN.equalsIgnoreCase(command)) {
                            issueToken(securityContext, arguments);
                        } else if (STATE.equalsIgnoreCase(command) || STATUS.equalsIgnoreCase(command)) {
                            writeLifecycleStatus(securityContext);
                        } else {
                            log.warn("SSH Transport Unknown command: {}", commandLine);
                            outputStream.write("Unknown command".getBytes(StandardCharsets.UTF_8));
                            returnCode = 127;
                        }
                    } catch (OrionSecurityException e) {
                        log.warn(e.getMessage());
                        writePlainError("ACCESS_DENIED");
                        returnCode = 10;
                    } catch (Exception e) {
                        log.warn("SSH Transport command failed: {}", commandLine, e);
                        writePlainError("Command failed");
                        returnCode = -1;
                    } finally {
                        exitCallback.onExit(returnCode);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("SSH command rejected, executor saturated: {}", commandLine);
                writePlainError("Service unavailable");
                exitCallback.onExit(1);
            }
        }

        private void writePlainError(String message) {
            try {
                errorStream.write(message.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("SSH Transport command failed to write response: {}", commandLine, e);
            }
        }

        private List<String> commandArguments(String commandLine) {
            String normalizedCommandLine = commandLine == null ? "" : commandLine.trim();
            if (normalizedCommandLine.isEmpty()) {
                return List.of("");
            }
            return List.of(normalizedCommandLine.split("\\s+"));
        }

        private void issueToken(SecurityContext securityContext, List<String> arguments) throws IOException {
            if (arguments.size() != 2) {
                throw new IllegalArgumentException("Usage: " + ISSUE_TOKEN + " <expires-in-seconds>");
            }
            long expiresInSeconds;
            try {
                expiresInSeconds = Long.parseLong(arguments.get(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Token expiration must be a number of seconds", e);
            }
            if (expiresInSeconds <= 0) {
                throw new IllegalArgumentException("Token expiration must be positive");
            }

            TokenIssueResult token = accessControlService.issueTokenFor(
                    securityContext.getUserIdentity(),
                    expiresInSeconds);
            switch (token) {
                case TokenIssueResult.Success(var value, var ignoredExpiresAtEpochSecond) ->
                        outputStream.write((value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                case TokenIssueResult.Failure(var reason, var throwable) ->
                        throw new IllegalStateException(reason, throwable);
            }
        }

        private void writeLifecycleStatus(SecurityContext securityContext) throws IOException, OrionSecurityException {
            accessEnforcer().require(
                    securityContext,
                    ApplicationAdminResource.applicationAdmin(),
                    ApplicationAccessRules.admin());
            outputStream.write((runtimeStateMachine.describeStatus() + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    @RequiredArgsConstructor
    private class GitSshCommand extends CloseOnDestroyCommand {
        private final String commandLine;

        @Override
        public void start(ChannelSession channelSession, Environment environment) {
            try {
                orionExecutor.submit(() -> {
                    int returnCode = 0;
                    SecurityContext securityContext = securityContextFor(channelSession);
                    try {
                        accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
                        serveGitCommand(channelSession, environment, securityContext);
                    } catch (OrionSecurityException e) {
                        writeProtocolError("ACCESS_DENIED");
                        returnCode = 10;
                    } catch (Exception e) {
                        log.error("Exception: ", e);
                        writeProtocolError(e.getMessage());
                        returnCode = -1;
                    } finally {
                        exitCallback.onExit(returnCode);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("Git SSH command rejected, executor saturated: {}", commandLine);
                writeProtocolError("Service unavailable");
                exitCallback.onExit(1);
            }
        }

        private void serveGitCommand(
                ChannelSession channelSession,
                Environment environment,
                SecurityContext securityContext) throws IOException {
            try (StandardStreams streams = StreamUtils.newInstance(
                    inputStream,
                    outputStream,
                    errorStream)) {
                GitByteBufTransportAdapter adapter =
                        new GitByteBufTransportAdapter(
                                UnpooledByteBufAllocator.DEFAULT,
                                nativeRepositoryProvider,
                                new AuthenticatedNativeRepositoryAccessHook(
                                        securityContext),
                                GitWireConfiguration.allSupported(),
                                packfileUriSourceFactory());
                try (InputStreamBufferedByteInput input =
                        new InputStreamBufferedByteInput(
                                streams.getInputStream(),
                                UnpooledByteBufAllocator.DEFAULT,
                                GitByteBufTransportAdapter
                                        .DEFAULT_INPUT_BUFFER_SIZE)) {
                    adapter.serveCommand(
                            initialRequestData(commandLine, environment),
                            input,
                            new OutputStreamBufferedByteOutput(
                                    streams.getOutputStream()));
                }
            }
        }

        private void writeProtocolError(String message) {
            try {
                GitBufferedByteTransportAdapter adapter =
                        new GitBufferedByteTransportAdapter(
                                null,
                                new OutputStreamBufferedByteOutput(outputStream),
                                UnpooledByteBufAllocator.DEFAULT);
                adapter.writeTextLine("ERR " + message);
                adapter.flush();
            } catch (IOException error) {
                log.warn("Failed to write SSH Git protocol error", error);
            }
        }
    }

    String readKey(InputStream inputStream) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(256);
        StringBuilder builder = new StringBuilder();
        Thread readingThread = Thread.currentThread();
        ScheduledFuture<?> watchdog = orionExecutor.schedule(
                readingThread::interrupt, setKeyReadTimeoutMillis, TimeUnit.MILLISECONDS);
        try (ReadableByteChannel rbc = Channels.newChannel(inputStream)) {
            // for US_ASCII 1-byte encoding we can decode by parts.
            while (rbc.read(bb.rewind()) >= 0) {
                builder.append(StandardCharsets.US_ASCII.decode(bb.flip()));
            }
        } finally {
            watchdog.cancel(false);
            // clear interrupt flag set by watchdog before thread returns to pool
            Thread.interrupted();
        }
        return builder.toString();
    }

    private SecurityContext securityContextFor(ChannelSession channelSession) {
        UserIdentity userIdentity = channelSession.getSession().getAttribute(SSH_AUTHENTICATED_USER);
        if (userIdentity == null) {
            log.warn("SSH session has no authenticated user attribute, treating as anonymous: {}",
                    channelSession.getSession());
        }
        return SecurityContext.createContext()
                .withUserIdentity(userIdentity)
                .withRequestId(channelSession.getSession().toString());
    }

    static InitialRequestData initialRequestData(
            String commandLine,
            Environment environment) {
        GitSshRequest request = parseGitSshCommand(commandLine);
        return new InitialRequestData(
                request.service(),
                request.repositoryPath(),
                null,
                gitProtocolParameters(environment));
    }

    private static GitSshRequest parseGitSshCommand(String commandLine) {
        String value = commandLine == null ? "" : commandLine.trim();
        int firstSeparator = firstWhitespace(value);
        if (firstSeparator <= 0) {
            throw new IllegalArgumentException(
                    "Malformed Git SSH command: " + commandLine);
        }
        String serviceName = value.substring(0, firstSeparator);
        String repository = parseRepositoryArgument(
                value.substring(firstSeparator).trim());
        return new GitSshRequest(
                InitialRequestService.fromWireName(serviceName),
                normalizeRepositoryPath(repository));
    }

    private static String parseRepositoryArgument(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Malformed Git SSH command: missing repository");
        }
        if (value.charAt(0) != '\'') {
            String[] parts = value.split("\\s+", -1);
            if (parts.length != 1) {
                throw new IllegalArgumentException(
                        "Malformed Git SSH command: too many arguments");
            }
            return parts[0];
        }
        int closingQuote = value.indexOf('\'', 1);
        if (closingQuote < 0
                || !value.substring(closingQuote + 1).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Malformed Git SSH command: invalid repository quoting");
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

    private static String normalizeRepositoryPath(String repository) {
        String normalized = repository == null ? "" : repository.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceFirst("\\.git$", "");
        if (normalized.isBlank()
                || normalized.contains("\0")
                || normalized.contains("\\")
                || normalized.contains("..")) {
            throw new IllegalArgumentException(
                    "Malformed Git SSH repository path");
        }
        return normalized;
    }

    private static Map<String, String> gitProtocolParameters(
            Environment environment) {
        if (environment == null) {
            return Map.of();
        }
        return gitProtocolParameters(environment.getEnv().get("GIT_PROTOCOL"));
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
            if ("version".equals(name) && !parameterValue.isEmpty()) {
                parameters.put(name, parameterValue);
            }
        }
        return Map.copyOf(parameters);
    }

    private NativePackfileUriSourceFactory packfileUriSourceFactory() {
        GitPackfileUriConfig packfileUri = gitTransportConfig == null
                ? null
                : gitTransportConfig.getPackfileUri();
        if (packfileUri == null
                || !packfileUri.isConfigured()
                || packfileUri.isAuto()) {
            return NativePackfileUriSourceFactory.NONE;
        }
        String baseUri = packfileUri.getBaseUri();
        return (data, repository) -> new PublishedPackfileUriSource(
                repository,
                packId -> NativePackfileUriBuilder.packUri(
                        baseUri,
                        data.getRepositoryPath(),
                        packId));
    }

    private record GitSshRequest(
            InitialRequestService service,
            String repositoryPath) {
    }
}
