package pro.deta.orion.git.workflow;

public final class GitClients {
    private GitClients() {
    }

    public static GitClient jgit() {
        return new JGitWorkflowClient("jgit");
    }

    public static GitClient git() {
        return new GitCliWorkflowClient("git", "git");
    }
}
