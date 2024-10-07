package pro.deta.orion.git.ssh;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.session.ServerSessionAware;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.git.GitInternalService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static pro.deta.orion.auth.check.PermissionChecks.permissionChecker;

@Slf4j
@RequiredArgsConstructor
public class SshCommandFactory implements CommandFactory {
    private final GitInternalService gitInternalService;
    private final Executor executor;

    @Override
    public Command createCommand(ChannelSession channelSession, String commandLine) throws IOException {
        if (commandLine.startsWith("git-"))
            return new GitSshCommand(channelSession, commandLine);
        else {
            return new OtherSshCommand(channelSession, commandLine);
        }

    }

    @RequiredArgsConstructor
    @Data
    private class OtherSshCommand implements Command, ServerSessionAware {
        private final ChannelSession channelSession;
        private final String commandLine;
        private InputStream inputStream;
        private OutputStream outputStream;
        private OutputStream errorStream;
        private ServerSession session;
        private ExitCallback exitCallback;

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            executor.execute(() -> {
                List<String> envs = env.getEnv().entrySet().stream().filter(e -> e.getKey().startsWith("GIT_")).map(e -> e.getValue()).collect(Collectors.toList());
                if ("set-key".equalsIgnoreCase(commandLine)) {
                    ByteBuffer bb = ByteBuffer.allocate(8192);
                    try {
                        Channels.newChannel(inputStream).read(bb);
                        String publicKey = StandardCharsets.US_ASCII.decode(bb.flip()).toString();
                        outputStream.write(("Public: " + publicKey).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    exitCallback.onExit(0);
                }
            });
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            log.warn("destroy {}", channelSession);
        }
    }

    @RequiredArgsConstructor
    @Data
    private class GitSshCommand implements Command, ServerSessionAware {
        private final ChannelSession channelSession;
        private final String commandLine;
        private InputStream inputStream;
        private OutputStream outputStream;
        private OutputStream errorStream;
        private ServerSession session;
        private ExitCallback exitCallback;

        @Override
        public void start(ChannelSession channelSession, Environment environment) throws IOException {
            permissionChecker().ALLOW_ANONYMOUS_ACCESS.assertThat(new UserIdentity("git-ssh-user"));
            executor.execute(() -> {
                List<String> envs = environment.getEnv().entrySet().stream().filter(e -> e.getKey().startsWith("GIT_")).map(e -> e.getValue()).collect(Collectors.toList());
                gitInternalService.service(channelSession.toString(), inputStream, outputStream, new BAOSTeeOutputStream(errorStream), "1", (inputStream) -> GitInternalService.parseGitCommand(commandLine, envs));
            });
        }

        @Override
        public void destroy(ChannelSession channelSession) throws Exception {
            log.warn("destroy {}", channelSession);
        }
    }

    public static class BAOSTeeOutputStream extends TeeOutputStream {
        public BAOSTeeOutputStream(OutputStream out) {
            super(out, new ByteArrayOutputStream());
        }

        public OutputStream getBranch() {
            return super.branch;
        }
    }
}
