package pro.deta.orion.git.workflow;

import java.io.IOException;

public final class GitServers {
    private GitServers() {
    }

    public static GitServer jgit() throws IOException {
        return JGitDaemonServer.start();
    }

    public static GitServer git() {
        return new GitDaemonServer("git");
    }
}
