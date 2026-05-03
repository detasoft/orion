package pro.deta.orion.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Git command classification")
class GitCommandTest {
    @Test
    @DisplayName("upload-pack is a read command")
    void uploadPackIsRead() {
        GitCommand command = GitInternalService.parseGitCommand("git-upload-pack /project.git", List.of());

        assertThat(command.getRepositoryName()).isEqualTo("project");
        assertThat(command.isRead()).isTrue();
        assertThat(command.isWrite()).isFalse();
    }

    @Test
    @DisplayName("receive-pack is a write command")
    void receivePackIsWrite() {
        GitCommand command = GitInternalService.parseGitCommand("git-receive-pack /project.git", List.of());

        assertThat(command.getRepositoryName()).isEqualTo("project");
        assertThat(command.isRead()).isFalse();
        assertThat(command.isWrite()).isTrue();
    }
}
