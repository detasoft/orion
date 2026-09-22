package pro.deta.orion.git.workflow;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Test-only SSH connector for JGit against the matrix's loopback allow-all server. */
final class MatrixSshSessionFactory extends SshSessionFactory {
    @Override
    public RemoteSession getSession(URIish uri, CredentialsProvider credentials, FS fs, int timeout) {
        return new RemoteSession() {
            private final List<Process> processes = new ArrayList<>();

            @Override
            public Process exec(String command, int commandTimeout) throws IOException {
                Process process = new ProcessBuilder("ssh", "-F", "/dev/null",
                        "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                        "-o", "UserKnownHostsFile=/dev/null", "-o", "GlobalKnownHostsFile=/dev/null",
                        "-o", "ConnectTimeout=5", "-p", Integer.toString(uri.getPort()),
                        uri.getUser() + "@" + uri.getHost(), command).start();
                processes.add(process);
                return process;
            }

            @Override
            public void disconnect() {
                for (Process process : processes) {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
            }
        };
    }

    @Override
    public String getType() {
        return "matrix-allow-all";
    }
}
