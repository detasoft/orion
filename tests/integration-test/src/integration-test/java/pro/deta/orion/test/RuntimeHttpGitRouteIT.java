package pro.deta.orion.test;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeHttpGitRouteIT {
    private static final String BRANCH = "master";

    @TempDir
    Path tempDir;

    @Test
    void jgitClientCanPushCloneAndFetchThroughHttpGitRoute() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git");
        String repositoryName = "http-project.git";
        Path serverRepository = createHttpWritableRepository(orionRoot, repositoryName);
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName).toString();
            Path sourceDirectory = tempDir.resolve("http-source");
            Path cloneDirectory = tempDir.resolve("http-clone");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId initialCommit = createCommit(source, "README.md", "hello over http\n", "initial http commit");
                Iterable<PushResult> pushResults = source.push()
                        .setRemote(remoteUrl)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();

                assertThat(pushResults)
                        .flatExtracting(PushResult::getRemoteUpdates)
                        .extracting(RemoteRefUpdate::getStatus)
                        .containsExactly(RemoteRefUpdate.Status.OK);
                assertRepositoryContains(serverRepository, initialCommit, "README.md", "hello over http\n");

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranch(BRANCH)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("hello over http\n");

                    ObjectId updatedCommit = createCommit(source, "README.md", "updated over http\n", "update http commit");
                    source.push()
                            .setRemote(remoteUrl)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                            .call();
                    assertRepositoryContains(serverRepository, updatedCommit, "README.md", "updated over http\n");

                    clone.fetch()
                            .setRemote("origin")
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH))
                            .call();
                    assertThat(clone.getRepository().resolve("refs/remotes/origin/" + BRANCH)).isEqualTo(updatedCommit);
                }
            }
        }

        assertThat(orionRoot.resolve("repos").resolve("r").resolve(repositoryName)).doesNotExist();
    }

    private static Path createHttpWritableRepository(Path orionRoot, String repositoryName) throws Exception {
        Path repositoryPath = orionRoot.resolve("repos").resolve(repositoryName);
        Files.createDirectories(repositoryPath);
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(repositoryPath.toFile())
                .setInitialBranch(BRANCH)
                .call()) {
            // The bare repository is served through /r/<repository>.git.
        }
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            repository.getConfig().setBoolean("http", null, "receivepack", true);
            repository.getConfig().save();
        }
        return repositoryPath;
    }

    private static Git initRepository(Path directory) throws Exception {
        Files.createDirectories(directory);
        Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch(BRANCH)
                .call();
        git.getRepository().getConfig().setString("user", null, "name", "HTTP Git Test");
        git.getRepository().getConfig().setString("user", null, "email", "http-git@example.test");
        git.getRepository().getConfig().save();
        return git;
    }

    private static ObjectId createCommit(Git git, String fileName, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(fileName), content);
        git.add().addFilepattern(fileName).call();
        return git.commit()
                .setAuthor("HTTP Git Test", "http-git@example.test")
                .setCommitter("HTTP Git Test", "http-git@example.test")
                .setMessage(message + " " + Instant.now())
                .call()
                .toObjectId();
    }

    private static void assertRepositoryContains(Path repositoryPath, ObjectId commitId, String fileName, String expectedContent)
            throws Exception {
        try (Repository repository = FileRepositoryBuilder.create(repositoryPath.toFile())) {
            assertThat(repository.exactRef("refs/heads/" + BRANCH).getObjectId()).isEqualTo(commitId);
            assertThat(repository.getObjectDatabase().has(commitId)).isTrue();
            assertThat(readFileFromCommit(repository, commitId, fileName)).isEqualTo(expectedContent);
        }
    }

    private static String readFileFromCommit(Repository repository, ObjectId commitId, String fileName) throws Exception {
        try (RevWalk revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, fileName, commit.getTree())) {
                assertThat(treeWalk).isNotNull();
                try (var reader = repository.newObjectReader()) {
                    return new String(reader.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                }
            }
        }
    }
}
