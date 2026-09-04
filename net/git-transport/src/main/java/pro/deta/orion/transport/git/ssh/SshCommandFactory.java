package pro.deta.orion.transport.git.ssh;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.SshKeyEnrollmentResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.render.PlainCommandRenderer;
import pro.deta.orion.command.render.RenderedCommand;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.schema.config.GitPackfileUriConfig;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriBuilder;
import pro.deta.orion.git.nativestorage.upload.PublishedPackfileUriSource;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.transport.git.auth.AuthenticatedRepositoryAccessHook;
import pro.deta.orion.transport.git.auth.RootSshKeyEnrollmentSession;
import pro.deta.orion.transport.git.auth.OrionSshAuthenticator;
import pro.deta.orion.util.stream.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Slf4j
public class SshCommandFactory implements CommandFactory {
    public static final String ISSUE_TOKEN = "issue-token";
    public static final String ENROLL_KEY = "enroll-key";
    public static final String STATE = "state";
    private static final GitPktLineWriter PKT_LINE_WRITER =
            new GitPktLineWriter();
    private final OrionExecutor orionExecutor;
    private final CommandDispatcher commandDispatcher;
    private final PlainCommandRenderer commandRenderer;
    private final GitNativeRepositoryService repositoryService;
    private final GitTransportConfig gitTransportConfig;
    private final OrionAccessControlService accessControlService;

    @Inject
    public SshCommandFactory(
            OrionExecutor orionExecutor,
            CommandDispatcher commandDispatcher,
            PlainCommandRenderer commandRenderer,
            GitNativeRepositoryService repositoryService,
            GitTransportConfig gitTransportConfig,
            OrionAccessControlService accessControlService) {
        this.orionExecutor = orionExecutor;
        this.commandDispatcher = commandDispatcher;
        this.commandRenderer = commandRenderer;
        this.repositoryService = repositoryService;
        this.gitTransportConfig = gitTransportConfig;
        this.accessControlService = accessControlService;
    }

    @Override
    public Command createCommand(ChannelSession channelSession, String commandLine) throws IOException {
        if (RootSshKeyEnrollmentSession.isRestricted(channelSession.getSession())) {
            return new RootEnrollmentCommand(ENROLL_KEY.equals(commandLine));
        }
        if (commandLine.startsWith("git-")) {
            return new GitSshCommand(commandLine);
        }
        return new OtherSshCommand(commandLine);
    }

    @RequiredArgsConstructor
    private final class RootEnrollmentCommand extends CloseOnDestroyCommand {
        private final boolean enrollmentRequested;

        @Override
        public void start(ChannelSession channel, Environment environment) {
            try {
                orionExecutor.submit(() -> execute(channel));
            } catch (RejectedExecutionException e) {
                finish(1, "Root SSH key enrollment failed.\n", errorStream);
            }
        }

        private void execute(ChannelSession channel) {
            if (!enrollmentRequested || accessControlService == null) {
                finish(1, "Root recovery permits only enroll-key.\n", errorStream);
                return;
            }
            RootSshKeyEnrollmentSession.PendingEnrollment pending =
                    RootSshKeyEnrollmentSession.pending(channel.getSession());
            if (pending == null) {
                finish(1, "Root SSH key enrollment failed.\n", errorStream);
                return;
            }
            SshKeyEnrollmentResult result = accessControlService.completeRootSshKeyEnrollment(
                    pending.expectedGeneration(),
                    pending.publicKeys());
            if (result instanceof SshKeyEnrollmentResult.Success) {
                RootSshKeyEnrollmentSession.complete(channel.getSession());
                finish(0, "Root SSH key enrolled. Reconnect with the enrolled key.\n", outputStream);
            } else {
                finish(1, "Root SSH key enrollment failed.\n", errorStream);
            }
        }

        private void finish(int exitCode, String message, OutputStream stream) {
            try {
                stream.write(message.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            } catch (IOException e) {
                log.warn("Root SSH key enrollment response delivery failed", e);
                exitCode = 1;
            }
            exitCallback.onExit(exitCode);
        }
    }

    @RequiredArgsConstructor
    private class OtherSshCommand extends CloseOnDestroyCommand {
        private final String commandLine;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final Object completionLock = new Object();

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            try {
                orionExecutor.submit(() -> execute(channel));
            } catch (RejectedExecutionException exception) {
                log.warn("SSH command rejected because the executor is saturated");
                deliver(failure(CommandFailureCode.HANDLER_FAILED, "Service unavailable"));
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            synchronized (completionLock) {
                cancelled.set(true);
                completeLocked(125);
            }
            super.destroy(channel);
        }

        private void execute(ChannelSession channel) {
            CommandResult result;
            try {
                result = commandDispatcher.dispatch(commandRequest(channel));
            } catch (RuntimeException exception) {
                log.warn("SSH command dispatcher failed", exception);
                result = failure(CommandFailureCode.HANDLER_FAILED, "Command handler failed");
            }
            deliver(result);
        }

        private CommandRequest commandRequest(ChannelSession channel) {
            String requestId = UUID.randomUUID().toString();
            UserIdentity identity = channel.getSession().getAttribute(SSH_AUTHENTICATED_USER);
            if (identity == null) {
                log.warn("SSH exec session has no authenticated user attribute");
            }
            SecurityContext securityContext = SecurityContext.createContext()
                    .withUserIdentity(identity)
                    .withSshConnectionCredentials(
                            OrionSshAuthenticator.connectionCredentials(channel.getSession()))
                    .withRequestId(requestId);
            CommandCancellation cancellation = () -> cancelled.get() || Thread.currentThread().isInterrupted();
            CommandContext context = new CommandContext(
                    securityContext,
                    requestId,
                    channel.getSession().toString(),
                    String.valueOf(channel.getSession().getRemoteAddress()),
                    CommandPath.root(),
                    CommandPresentation.plain(),
                    cancellation,
                    Map.of("transport", "ssh", "requestType", "exec"));
            return new CommandRequest(commandLine, context);
        }

        private void deliver(CommandResult result) {
            RenderedCommand rendered = commandRenderer.render(result);
            try {
                outputStream.write(rendered.stdout().getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                errorStream.write(rendered.stderr().getBytes(StandardCharsets.UTF_8));
                errorStream.flush();
                complete(rendered.exitCode());
            } catch (IOException exception) {
                log.warn("SSH command response delivery failed", exception);
                complete(1);
            }
        }

        private void complete(int exitCode) {
            synchronized (completionLock) {
                completeLocked(cancelled.get() ? 125 : exitCode);
            }
        }

        private void completeLocked(int exitCode) {
            if (completed.compareAndSet(false, true)) {
                exitCallback.onExit(exitCode);
            }
        }

        private CommandResult.Failure failure(CommandFailureCode code, String message) {
            return new CommandResult.Failure(code, message, List.of());
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
                try (InputStreamBufferedByteInput input =
                        new InputStreamBufferedByteInput(
                                streams.getInputStream())) {
                    OutputStreamBufferedByteOutput output =
                            new OutputStreamBufferedByteOutput(
                                    streams.getOutputStream());
                    GitWireBootstrap bootstrap = GitWireBootstrap.sshCommand(
                            input,
                            output,
                            commandLine,
                            gitProtocol(environment));
                    try {
                        new GitBlockingWireSession(
                                repositoryService,
                                new AuthenticatedRepositoryAccessHook(
                                        securityContext),
                                GitWireConfiguration.allSupported(),
                                packfileUriSourceFactory(),
                                bootstrap.wire())
                                .serveCommand(bootstrap.data());
                    } catch (Exception error) {
                        writeGitProtocolException(
                                streams.getOutputStream(),
                                commandLine,
                                error);
                        throw error;
                    }
                }
            }
        }

        private void writeProtocolError(String message) {
            try {
                writeGitProtocolError(outputStream, message);
            } catch (IOException error) {
                log.warn("Failed to write SSH Git protocol error", error);
            }
        }

    }

    static void writeGitProtocolException(
            OutputStream outputStream,
            String commandLine,
            Throwable error) throws IOException {
        Objects.requireNonNull(outputStream, "outputStream");
        Objects.requireNonNull(error, "error");
        if (isReceivePack(commandLine)) {
            writeSidebandError(outputStream, stackTrace(error));
            return;
        }
        writeGitProtocolError(outputStream, error.getMessage());
    }

    private static void writeGitProtocolError(
            OutputStream outputStream,
            String message) throws IOException {
        OutputStreamBufferedByteOutput output =
                new OutputStreamBufferedByteOutput(outputStream);
        writePktLine(
                output,
                ("ERR " + message + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeSidebandError(
            OutputStream outputStream,
            String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        OutputStreamBufferedByteOutput output =
                new OutputStreamBufferedByteOutput(outputStream);
        for (byte[] packet : PKT_LINE_WRITER.writeSidebandPackets(3, payload)) {
            output.write(packet);
        }
        output.flush();
    }

    private static void writePktLine(
            OutputStreamBufferedByteOutput output,
            byte[] payload) throws IOException {
        output.write(PKT_LINE_WRITER.writeDataHeader(payload.length));
        output.write(payload);
    }

    private static boolean isReceivePack(String commandLine) {
        return commandLine != null
                && commandLine.trim().startsWith(
                        InitialRequestService.RECEIVE_PACK.wireName());
    }

    private static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private SecurityContext securityContextFor(ChannelSession channelSession) {
        UserIdentity userIdentity = channelSession.getSession().getAttribute(SSH_AUTHENTICATED_USER);
        if (userIdentity == null) {
            log.warn("SSH session has no authenticated user attribute, treating as anonymous: {}",
                    channelSession.getSession());
        }
        return SecurityContext.createContext()
                .withUserIdentity(userIdentity)
                .withSshConnectionCredentials(
                        OrionSshAuthenticator.connectionCredentials(channelSession.getSession()))
                .withRequestId(channelSession.getSession().toString());
    }

    static InitialRequestData initialRequestData(
            String commandLine,
            Environment environment) {
        return GitWireBootstrap.sshCommandData(
                commandLine,
                gitProtocol(environment));
    }

    private static String gitProtocol(Environment environment) {
        if (environment == null) {
            return null;
        }
        return environment.getEnv().get("GIT_PROTOCOL");
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

}
