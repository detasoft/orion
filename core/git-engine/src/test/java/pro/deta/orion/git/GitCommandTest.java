package pro.deta.orion.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("nested repository names are preserved")
    void nestedRepositoryNamesArePreserved() {
        GitCommand command = GitInternalService.parseGitCommand("git-upload-pack /team/project.git", List.of());

        assertThat(command.getRepositoryName()).isEqualTo("team/project");
    }

    @Test
    @DisplayName("properties keep everything after the first equals sign")
    void propertiesKeepEqualsSignsInValues() {
        GitCommand command = GitInternalService.parseGitCommand(
                "git-upload-pack /project.git\0host=git.example.com=9418\0version=2",
                List.of("GIT_PROTOCOL=version=2"));

        assertThat(command.getProperties())
                .containsEntry("host", "git.example.com=9418")
                .containsEntry("version", "2")
                .containsEntry("GIT_PROTOCOL", "version=2");
    }

    @Test
    @DisplayName("malformed commands fail with an argument error")
    void malformedCommandsFailWithArgumentError() {
        for (String command : List.of("", "   ", "git-upload-pack", "git-upload-pack\0host=git.example.com")) {
            assertThatThrownBy(() -> GitInternalService.parseGitCommand(command, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Malformed git command");
        }
    }
}
