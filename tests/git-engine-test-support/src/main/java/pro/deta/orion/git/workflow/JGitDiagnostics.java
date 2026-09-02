package pro.deta.orion.git.workflow;

import org.eclipse.jgit.lib.Repository;

final class JGitDiagnostics {
    private JGitDiagnostics() {
    }

    static String version() {
        String version = Repository.class.getPackage().getImplementationVersion();
        return "JGit/" + (version == null ? "unknown" : version);
    }
}
