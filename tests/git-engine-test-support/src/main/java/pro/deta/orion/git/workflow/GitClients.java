package pro.deta.orion.git.workflow;

public final class GitClients {
    private GitClients() {
    }

    public static GitClient jgit() {
        return new JGitWorkflowClient("jgit");
    }

    public static GitClient nativeGit() {
        return new NativeGitWorkflowClient("native-git", "git");
    }
}
