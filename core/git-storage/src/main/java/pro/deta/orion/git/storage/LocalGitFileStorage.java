package pro.deta.orion.git.storage;

import pro.deta.orion.git.common.GitCommitAuthor;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.common.GitRepositoryFileSnapshot;
import pro.deta.orion.git.jgit.JGitRepository;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class LocalGitFileStorage {
    private final String repositoryName;
    private final Path repositoryPath;

    public LocalGitFileStorage(Path repositoryPath) {
        this.repositoryName = repositoryName(repositoryPath);
        this.repositoryPath = Objects.requireNonNull(repositoryPath, "repositoryPath").toAbsolutePath().normalize();
    }

    public Result<GitFileSnapshot> load(String branch, String path) {
        return load(branch, List.of(path));
    }

    public Result<GitFileSnapshot> load(String branch, List<String> paths) {
        try (GitRepository repository = JGitRepository.open(repositoryName, repositoryPath, false)) {
            GitRepositoryFileSnapshot snapshot = repository.loadFiles(branch, paths);
            return new Result.Success<>(new GitFileSnapshot(snapshot.files(), snapshot.version()));
        } catch (GitRepositoryFileNotFoundException e) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (IOException | GitOperationException | IllegalArgumentException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    public void save(String branch, Map<String, byte[]> files, String message, UserEmail author) {
        try (GitRepository repository = JGitRepository.open(repositoryName, repositoryPath, true)) {
            repository.saveFiles(branch, files, message, gitAuthor(author));
        } catch (IOException | GitOperationException e) {
            throw new RuntimeException("Cannot save files to local git repository " + repositoryPath, e);
        }
    }

    private static String repositoryName(Path repositoryPath) {
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        Path fileName = repositoryPath.toAbsolutePath().normalize().getFileName();
        if (fileName == null) {
            return "local";
        }
        return fileName.toString();
    }

    private static GitCommitAuthor gitAuthor(UserEmail author) {
        if (author == null) {
            return GitCommitAuthor.EMPTY;
        }
        return new GitCommitAuthor(author.getUsername(), author.getEmail());
    }
}
