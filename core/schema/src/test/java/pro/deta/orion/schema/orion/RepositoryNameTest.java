package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryNameTest {
    @Test
    void canonicalizesOrdinaryRepositoryNames() {
        assertThat(RepositoryName.parse("orion").value()).isEqualTo("orion");
        assertThat(RepositoryName.parse("api_v2.1").value()).isEqualTo("api_v2.1");
        assertThat(RepositoryName.parse("org/team/repo").value()).isEqualTo("org/team/repo");
        assertThat(RepositoryName.parse("team%2Frepo").value()).isEqualTo("team/repo");
        assertThat(RepositoryName.parse("team%5Crepo").value()).isEqualTo("team/repo");
        assertThat(RepositoryName.parse("team\\repo").value()).isEqualTo("team/repo");
        assertThat(RepositoryName.parse("api%5fv2%2e1").value()).isEqualTo("api_v2.1");
    }

    @Test
    void removesOneGitDecorationPair() {
        assertThat(RepositoryName.fromGitPath("/org/team/repo%2Egit").value())
                .isEqualTo("org/team/repo");
        assertThat(RepositoryName.fromGitPath("team\\repo.git").value())
                .isEqualTo("team/repo");
        assertThat(RepositoryName.fromGitPath("repo").value()).isEqualTo("repo");
    }

    @Test
    void hasValueSemantics() {
        RepositoryName encoded = RepositoryName.parse("team%2Frepo");
        RepositoryName plain = RepositoryName.parse("team/repo");

        assertThat(encoded).isEqualTo(plain).hasSameHashCodeAs(plain);
        assertThat(encoded).hasToString("team/repo");
    }

    @Test
    void rejectsInvalidOrdinaryRepositoryNames() {
        assertThatThrownBy(() -> RepositoryName.parse(null))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> invalid = List.of(
                "", " ", "repo ", "Repo", "répo", "r%C3%A9po", "repo+other",
                "%", "%2", "%GG", "%６１", "%C3%28", "%FF", "%252F", "/repo", "repo/",
                "repo//other", ".", "..", "repo/.", "repo/%2E%2E", "-repo", "repo-",
                "repo..other", "repo.-other", "repo.git", "repo\u0000other");

        for (String value : invalid) {
            assertThatThrownBy(() -> RepositoryName.parse(value))
                    .as("repository name %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidGitRepositoryPaths() {
        for (String value : List.of(
                "//repo", "repo/", "repo.git.git", "/repo.git.git", "/../repo.git",
                "/%2E%2E/repo.git", "/repo%252Egit", "/Repo.git", "/r%C3%A9po.git")) {
            assertThatThrownBy(() -> RepositoryName.fromGitPath(value))
                    .as("Git repository path %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
