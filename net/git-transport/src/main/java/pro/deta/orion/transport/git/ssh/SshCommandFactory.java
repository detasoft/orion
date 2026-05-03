package pro.deta.orion.transport.git.ssh;

import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import pro.deta.orion.auth.Permission;
import pro.deta.orion.auth.SecurityContextHolder;
import pro.deta.orion.auth.check.OrionSecurityException;
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
import java.util.List;
import java.util.stream.Collectors;

import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.PermissionChecks.permissionChecker;
import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SshCommandFactory implements CommandFactory {
    public static final String SET_KEY = "set-key";
    public static final String SHUTDOWN = "shutdown";
    private final GitInternalService gitInternalService;
    private final OrionExecutor orionExecutor;
    private final OrionProvider orionProvider;

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
                try (SecurityContextHolder ignored = new SecurityContextHolder()) {
                    getSc().setUserIdentity(channel.getSession().getAttribute(SSH_AUTHENTICATED_USER));
                    getSc().with(Permission.REQUEST_ID, channel.getSession().toString());
                    permissionChecker().ALLOW_ANONYMOUS_ACCESS.assertThat();

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
                            orionExecutor.submit(() -> orionProvider.getAccessControlService().addKeyToUser(username, publicKey));
                            outputStream.write(("Public: " + publicKey + " added successfully as authentication method for user " + username).getBytes(StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (SHUTDOWN.equalsIgnoreCase(commandLine)) {
                        permissionChecker().ALLOW_TO_SHUTDOWN.assertThat(SHUTDOWN);
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
                try (SecurityContextHolder ignored = new SecurityContextHolder()) {
                    getSc().setUserIdentity(channelSession.getSession().getAttribute(SSH_AUTHENTICATED_USER));
                    getSc().with(Permission.REQUEST_ID, channelSession.getSession().toString());
                    permissionChecker().ALLOW_ANONYMOUS_ACCESS.assertThat();
                    List<String> envs = environment.getEnv().entrySet().stream().filter(e -> e.getKey().startsWith("GIT_")).map(e -> e.getValue()).collect(Collectors.toList());
                    try (IOEStreamProvider streams = StreamUtils.newInstance(inputStream, outputStream, errorStream)) {
                        gitInternalService.service(channelSession.toString(), streams, "1", (inputStream) -> GitInternalService.parseGitCommand(commandLine, envs));
                    }
                } catch (OrionSecurityException e) {
                    GitUtils.writeErrorIntoOS(outputStream, "ACCESS_DENIED");
                    returnCode = 10;
                } catch (Exception e) {
                    log.error("Exception: ", e);
                    GitUtils.writeErrorIntoOS(outputStream, e.getMessage());
                    returnCode = -1;
                } finally {
                    exitCallback.onExit(returnCode);
                }
            });
        }
    }
}
