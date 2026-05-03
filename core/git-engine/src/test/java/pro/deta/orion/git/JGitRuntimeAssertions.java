package pro.deta.orion.git;

import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.config.schema.OrionConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

final class JGitRuntimeAssertions {
    private JGitRuntimeAssertions() {
    }

    static void installDefaultControlledJGitRuntime() {
        ControlledOrionJGitSystemReader reader = new ControlledOrionJGitSystemReader(new OrionConfiguration.JGitConfig());
        new OrionJGitRuntime(reader).install();
        assertControlledJGitSystemReaderInstalled();
    }

    static void assertControlledJGitSystemReaderInstalled() {
        assertThat(SystemReader.getInstance())
                .as("JGit SystemReader must stay controlled by Orion")
                .isInstanceOf(ControlledOrionJGitSystemReader.class);
    }
}
