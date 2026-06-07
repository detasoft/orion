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
import pro.deta.orion.git.GitInternalService;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.stream.*;

import jakarta.inject.Named;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private final GitInternalService gitInternalService;
    private final OrionExecutor orionExecutor;
    private final OrionProvider orionProvider;
    private final OrionAccessControlService accessControlService;
    private final AggregateStateMachine transportStateMachine;
    private final long setKeyReadTimeoutMillis;

    @Inject
    public SshCommandFactory(
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor,
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            @Named("transport") AggregateStateMachine transportStateMachine) {
        this(gitInternalService, orionExecutor, orionProvider, accessControlService,
                transportStateMachine, 30_000);
    }

    SshCommandFactory(
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor,
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            AggregateStateMachine transportStateMachine,
            long setKeyReadTimeoutMillis) {
        this.gitInternalService = gitInternalService;
        this.orionExecutor = orionExecutor;
        this.orionProvider = orionProvider;
        this.accessControlService = accessControlService;
        this.transportStateMachine = transportStateMachine;
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
            outputStream.write((transportStateMachine.describeStatus() + System.lineSeparator())
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
                        List<String> envs = gitEnvironmentValues(environment);
                        try (StandardStreams streams = StreamUtils.newInstance(inputStream, outputStream, errorStream)) {
                            gitInternalService.service(
                                    securityContext,
                                    channelSession.toString(),
                                    streams,
                                    securityContext.getRequestId(),
                                    inputStream -> GitInternalService.parseGitCommand(commandLine, envs));
                        }
                    } catch (OrionSecurityException e) {
                        GitUtils.writeProtocolError(outputStream, "ACCESS_DENIED");
                        returnCode = 10;
                    } catch (Exception e) {
                        log.error("Exception: ", e);
                        GitUtils.writeProtocolError(outputStream, e.getMessage());
                        returnCode = -1;
                    } finally {
                        exitCallback.onExit(returnCode);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("Git SSH command rejected, executor saturated: {}", commandLine);
                GitUtils.writeProtocolError(outputStream, "Service unavailable");
                exitCallback.onExit(1);
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

    private static List<String> gitEnvironmentValues(Environment environment) {
        List<String> values = new ArrayList<>();
        for (var entry : environment.getEnv().entrySet()) {
            if (entry.getKey().startsWith("GIT_")) {
                values.add(entry.getValue());
            }
        }
        return values;
    }
}
