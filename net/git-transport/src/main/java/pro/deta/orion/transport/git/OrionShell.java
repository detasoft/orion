package pro.deta.orion.transport.git;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.eclipse.jgit.lib.Constants;
import pro.deta.orion.transport.git.ssh.CloseOnDestroyCommand;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.MessageFormat;

import static pro.deta.orion.transport.git.ssh.SshCommandFactory.SET_KEY;

public class OrionShell extends InteractiveProcessShellFactory {
    @Override
    public Command createShell(ChannelSession channel) {
        if ("git".equalsIgnoreCase(channel.getSession().getUsername())) {
            return new SendMessage(channel);
        } else
            return super.createShell(channel);
    }

    private static class SendMessage extends CloseOnDestroyCommand {
        private final ChannelSession channel;
        private InetSocketAddress localAddress;

        public SendMessage(ChannelSession channel) {
            this.channel = channel;
            localAddress = (InetSocketAddress) channel.getServerSession().getLocalAddress();
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            errorStream.write(Constants.encode(getMessage()));
            errorStream.flush();

            exitCallback.onExit(127);
        }

        String getMessage() {
            String username = channel.getSession().getUsername();
            String hostname = getHostname();
            int port = localAddress.getPort();


            final String b2 = "─".repeat(72);
            final String nl = "\r\n";

            StringBuilder msg = new StringBuilder();
            msg.append(b2);
            msg.append(nl);
            msg.append(" You may clone a repository with the following Git syntax:");
            msg.append(nl);

            msg.append("   git clone ");
            msg.append(formatUrl(hostname, port, username));
            msg.append(nl);

            msg.append(b2);
            msg.append(nl);

            msg.append(" You may upload an SSH public key with the following syntax:");
            msg.append(nl);

            msg.append(String.format("   cat ~/.ssh/id_rsa.pub | ssh -l %s -p %d %s " + SET_KEY, username, port, hostname));
            msg.append(nl);

            msg.append(b2);
            msg.append(nl);

            return msg.toString();
        }

        private String getHostname() {
            return localAddress.getHostName();
        }

        private String formatUrl(String hostname, int port, String username) {
            int displayPort = localAddress.getPort();
            String displayHostname = localAddress.getHostName();
            if(displayHostname.isEmpty()) {
                displayHostname = hostname;
            }
            if (displayPort == 22) {
                // standard port
                return MessageFormat.format("{0}@{1}/REPOSITORY.git", username, displayHostname);
            } else {
                // non-standard port
                return MessageFormat.format("ssh://{0}@{1}:{2,number,0}/REPOSITORY.git",
                        username, displayHostname, displayPort);
            }
        }
    }
}
