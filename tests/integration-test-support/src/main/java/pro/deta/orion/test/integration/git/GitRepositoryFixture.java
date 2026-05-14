package pro.deta.orion.test.integration.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public final class GitRepositoryFixture {
    private GitRepositoryFixture() {
    }

    public static ObjectId seedBareRepository(
            Path bareRepository,
            Path worktree,
            String branch,
            Map<String, byte[]> files) throws Exception {
        try (Git ignored = Git.init()
                .setBare(true)
                .setGitDir(bareRepository.toFile())
                .setInitialBranch(branch)
                .call()) {
            // The bare repository is used as the remote for the seeded worktree.
        }

        try (Git seed = Git.init()
                .setDirectory(worktree.toFile())
                .setInitialBranch(branch)
                .call()) {
            seed.getRepository().getConfig().setString("user", null, "name", "Integration Test");
            seed.getRepository().getConfig().setString("user", null, "email", "integration@example.test");
            seed.getRepository().getConfig().save();

            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path file = worktree.resolve(entry.getKey());
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.write(file, entry.getValue());
                seed.add().addFilepattern(entry.getKey()).call();
            }
            ObjectId commitId = seed.commit()
                    .setAuthor("Integration Test", "integration@example.test")
                    .setCommitter("Integration Test", "integration@example.test")
                    .setMessage("seed integration repository " + Instant.now())
                    .call()
                    .toObjectId();
            seed.push()
                    .setRemote(bareRepository.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .call();
            return commitId;
        }
    }
}
