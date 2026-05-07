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
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.GitInternalService;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.stream.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SshCommandFactory implements CommandFactory {
    public static final String SET_KEY = "set-key";
    public static final String SHUTDOWN = "shutdown";
    private final GitInternalService gitInternalService;
    private final OrionExecutor orionExecutor;
    private final OrionProvider orionProvider;
    private final OrionAccessControlService accessControlService;

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
            orionExecutor.submit(() -> {
                int returnCode = 0;
                SecurityContext securityContext = securityContextFor(channel);
                try {
                    accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());

                    if (SET_KEY.equalsIgnoreCase(commandLine)) {
                        ByteBuffer bb = ByteBuffer.allocate(256);
                        try {
                            String username = channel.getSession().getUsername();
                            StringBuilder publicKeyBuilder = new StringBuilder();
                            ReadableByteChannel readableByteChannel = Channels.newChannel(inputStream);
                            // for US_ASCII 1-byte encoding we can decode by parts.
                            while (readableByteChannel.read(bb.rewind()) >= 0) {
                                publicKeyBuilder.append(StandardCharsets.US_ASCII.decode(bb.flip()));
                            }
                            String publicKey = publicKeyBuilder.toString();
                            orionExecutor.submit(() -> accessControlService.addKeyToUser(username, publicKey));
                            outputStream.write(("Public: " + publicKey + " added successfully as authentication method for user " + username).getBytes(StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (SHUTDOWN.equalsIgnoreCase(commandLine)) {
                        accessEnforcer().require(
                                securityContext,
                                ApplicationShutdownResource.applicationShutdown(),
                                ApplicationAccessRules.shutdown());
                        orionExecutor.submit(() -> orionProvider.getOrionApplicationLifecycle().beginShutdown());
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
        }

        private void writePlainError(String message) {
            try {
                outputStream.write(message.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("SSH Transport command failed to write response: {}", commandLine, e);
            }
        }
    }

    @RequiredArgsConstructor
    private class GitSshCommand extends CloseOnDestroyCommand {
        private final String commandLine;

        @Override
        public void start(ChannelSession channelSession, Environment environment) {
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
        }
    }

    private static SecurityContext securityContextFor(ChannelSession channelSession) {
        return SecurityContext.createContext()
                .withUserIdentity(channelSession.getSession().getAttribute(SSH_AUTHENTICATED_USER))
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
