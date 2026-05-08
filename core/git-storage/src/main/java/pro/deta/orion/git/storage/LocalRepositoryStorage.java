package pro.deta.orion.git.storage;

import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileNotFoundException;
import pro.deta.orion.git.jgit.JGitRepository;
import pro.deta.orion.util.Result;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

import static pro.deta.orion.util.Result.FailureCode.GENERAL;
import static pro.deta.orion.util.Result.FailureCode.NOT_FOUND;

public final class LocalRepositoryStorage implements RepositoryStorage {
    private static final String FILE_SCHEME = "file";

    private final Path storageRoot;
    private final boolean createIfMissing;

    public LocalRepositoryStorage(RepositoryStorageLocator locator, boolean createIfMissing) {
        Objects.requireNonNull(locator, "locator");
        if (!supports(locator)) {
            throw new IllegalArgumentException("Unsupported local repository storage locator: " + locator.location());
        }
        this.storageRoot = storageRootFrom(locator);
        this.createIfMissing = createIfMissing;
    }

    @Override
    public boolean supports(RepositoryStorageLocator locator) {
        return locator != null && FILE_SCHEME.equalsIgnoreCase(locator.scheme());
    }

    @Override
    public Result<GitRepository> open(String repositoryName) {
        try {
            Path relativePath = repositoryRelativePath(repositoryName);
            Path repositoryPath = storageRoot.resolve(relativePath).normalize();
            if (!repositoryPath.startsWith(storageRoot) || repositoryPath.equals(storageRoot)) {
                return new Result.Failure<>(GENERAL, "Repository path escapes storage root");
            }
            return new Result.Success<>(JGitRepository.open(repositoryName(relativePath), repositoryPath, createIfMissing));
        } catch (GitRepositoryFileNotFoundException e) {
            return new Result.Failure<>(NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return new Result.Failure<>(GENERAL, e.getMessage(), e);
        } catch (IOException e) {
            return new Result.Failure<>(GENERAL, e.getMessage(), e);
        }
    }

    private static Path storageRootFrom(RepositoryStorageLocator locator) {
        Path path;
        if (hasExplicitFileScheme(locator.location())) {
            URI uri = URI.create(locator.location());
            if (uri.isOpaque()) {
                path = Path.of(uri.getSchemeSpecificPart());
            } else {
                path = Path.of(uri);
            }
        } else {
            path = Path.of(locator.location());
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean hasExplicitFileScheme(String location) {
        return location.regionMatches(true, 0, FILE_SCHEME + ":", 0, (FILE_SCHEME + ":").length());
    }

    private static Path repositoryRelativePath(String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("Repository name must not be empty");
        }

        Path relativePath = Path.of(repositoryName);
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Repository name must be relative");
        }
        for (Path segment : relativePath) {
            String segmentName = segment.toString();
            if (segmentName.isBlank() || ".".equals(segmentName) || "..".equals(segmentName)) {
                throw new IllegalArgumentException("Repository name contains an unsafe path segment");
            }
        }

        Path normalizedPath = relativePath.normalize();
        if (normalizedPath.getNameCount() == 0) {
            throw new IllegalArgumentException("Repository name must not resolve to storage root");
        }
        return normalizedPath;
    }

    private static String repositoryName(Path relativePath) {
        return relativePath.toString().replace(File.separatorChar, '/');
    }

}
