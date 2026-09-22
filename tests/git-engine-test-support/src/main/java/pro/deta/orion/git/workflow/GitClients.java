package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshTransport;

public final class GitClients {
    private GitClients() {
    }

    public static GitClient jgit() {
        return new JGitWorkflowClient("jgit");
    }

    public static GitClient git() {
        return new GitCliWorkflowClient("git", "git");
    }

    public static GitClient jgitAllowAllSsh() {
        return new JGitWorkflowClient("jgit", allowAllSsh());
    }

    public static TransportConfigCallback allowAllSsh() {
        return transport -> {
            if (transport instanceof SshTransport ssh) {
                ssh.setSshSessionFactory(new MatrixSshSessionFactory());
            }
        };
    }
}
