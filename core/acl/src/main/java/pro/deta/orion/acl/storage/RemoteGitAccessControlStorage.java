package pro.deta.orion.acl.storage;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RemoteGitAccessControlStorage implements AccessControlStorage {
    private static final String REMOTE_NAME = "origin";
    private static final RefSpec FETCH_BRANCHES = new RefSpec("+refs/heads/*:refs/remotes/" + REMOTE_NAME + "/*");

    private final OrionConfiguration.BootstrapAccessControlConfig config;
    private final Path worktree;
    private final String remoteUri;
    private final CredentialsProvider credentialsProvider;
    private final TransportConfigCallback transportConfigCallback;

    public RemoteGitAccessControlStorage(
            OrionConfiguration configuration,
            OrionConfiguration.BootstrapAccessControlConfig config) {
        this(
                remoteWorktree(configuration, config),
                config,
                normalizeRemoteUri(config.getLocation()));
    }

    RemoteGitAccessControlStorage(Path worktree, OrionConfiguration.BootstrapAccessControlConfig config) {
        this(worktree, config, normalizeRemoteUri(config.getLocation()));
    }

    private RemoteGitAccessControlStorage(
            Path worktree,
            OrionConfiguration.BootstrapAccessControlConfig config,
            String remoteUri) {
        this.config = Objects.requireNonNull(config, "config");
        this.worktree = Objects.requireNonNull(worktree, "worktree").toAbsolutePath().normalize();
        this.remoteUri = Objects.requireNonNull(remoteUri, "remoteUri");
        this.credentialsProvider = credentialsProvider(config.getAuth());
        this.transportConfigCallback = transportConfigCallback(config.getAuth(), this.worktree);
        if (config.getPaths() == null || config.getPaths().isEmpty()) {
            throw new IllegalArgumentException("At least one ACL path must be configured");
        }
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        try (Git git = openRepository()) {
            fetch(git);
            if (!checkoutExistingBranch(git)) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }

            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String path : config.getPaths()) {
                Path file = worktreePath(path);
                if (!Files.exists(file)) {
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
                }
                files.put(path, Files.readAllBytes(file));
            }
            ObjectId head = git.getRepository().resolve("HEAD");
            return new Result.Success<>(new AccessControlSnapshot(
                    files,
                    head == null ? java.util.Optional.empty() : java.util.Optional.of(head.name())));
        } catch (GitAPIException | IOException | IllegalArgumentException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        validateSnapshotPaths(snapshot);
        try (Git git = openRepository()) {
            fetch(git);
            checkoutBranchForSave(git);
            for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                Path file = worktreePath(entry.getKey());
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.write(file, entry.getValue());
                git.add().addFilepattern(gitPath(entry.getKey())).call();
            }
            if (!git.status().call().isClean()) {
                UserEmail author = request.author();
                git.commit()
                        .setMessage(request.message())
                        .setAuthor(authorName(author), authorEmail(author))
                        .setCommitter(authorName(author), authorEmail(author))
                        .call();
            }
            configure(git.push()
                    .setRemote(REMOTE_NAME)
                    .setRefSpecs(new RefSpec("refs/heads/" + config.getBranch() + ":refs/heads/" + config.getBranch())))
                    .call();
        } catch (GitAPIException | IOException | IllegalArgumentException e) {
            throw new RuntimeException("Cannot save remote Git ACL configuration", e);
        }
    }

    @Override
    public String primaryPath() {
        return config.primaryPath();
    }

    static boolean supportsLocation(String location) {
        ResourceScheme scheme = ResourceLocation.parse(location, "ACL location").scheme();
        return scheme instanceof ResourceScheme.Other other
                && ("git+file".equals(other.value())
                || "git+ssh".equals(other.value())
                || "git+https".equals(other.value()));
    }

    static String normalizeRemoteUri(String location) {
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        if (!(resourceLocation.scheme() instanceof ResourceScheme.Other other)) {
            throw new IllegalArgumentException("Unsupported remote Git ACL location: " + location);
        }
        return switch (other.value()) {
            case "git+file" -> resourceLocation.withScheme("file").raw();
            case "git+ssh" -> resourceLocation.withScheme("ssh").raw();
            case "git+https" -> resourceLocation.withScheme("https").raw();
            default -> throw new IllegalArgumentException("Unsupported remote Git ACL location: " + location);
        };
    }

    private Git openRepository() throws GitAPIException, IOException {
        if (Files.exists(worktree.resolve(".git"))) {
            return Git.open(worktree.toFile());
        }
        if (Files.exists(worktree)) {
            throw new IOException("Remote ACL worktree exists but is not a Git repository: " + worktree);
        }
        if (worktree.getParent() != null) {
            Files.createDirectories(worktree.getParent());
        }
        return configure(Git.cloneRepository()
                .setURI(remoteUri)
                .setDirectory(worktree.toFile())
                .setNoCheckout(true))
                .call();
    }

    private void fetch(Git git) throws GitAPIException {
        configure(git.fetch()
                .setRemote(REMOTE_NAME)
                .setRefSpecs(FETCH_BRANCHES))
                .call();
    }

    private boolean checkoutExistingBranch(Git git) throws IOException, GitAPIException {
        Repository repository = git.getRepository();
        Ref remoteBranch = repository.exactRef(remoteBranchRef());
        if (remoteBranch != null) {
            checkoutRemoteBranch(git, repository);
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(REMOTE_NAME + "/" + config.getBranch()).call();
            return true;
        }
        Ref localBranch = repository.exactRef(localBranchRef());
        if (localBranch != null) {
            git.checkout().setName(config.getBranch()).call();
            return true;
        }
        return false;
    }

    private void checkoutBranchForSave(Git git) throws IOException, GitAPIException {
        Repository repository = git.getRepository();
        Ref remoteBranch = repository.exactRef(remoteBranchRef());
        if (remoteBranch != null) {
            checkoutRemoteBranch(git, repository);
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(REMOTE_NAME + "/" + config.getBranch()).call();
            return;
        }
        Ref localBranch = repository.exactRef(localBranchRef());
        if (localBranch != null) {
            git.checkout().setName(config.getBranch()).call();
            return;
        }
        linkHeadToBranch(repository);
    }

    private void checkoutRemoteBranch(Git git, Repository repository) throws IOException, GitAPIException {
        if (repository.exactRef(localBranchRef()) == null) {
            git.checkout()
                    .setCreateBranch(true)
                    .setName(config.getBranch())
                    .setStartPoint(REMOTE_NAME + "/" + config.getBranch())
                    .call();
        } else {
            git.checkout().setName(config.getBranch()).call();
        }
    }

    private String localBranchRef() {
        return "refs/heads/" + config.getBranch();
    }

    private String remoteBranchRef() {
        return "refs/remotes/" + REMOTE_NAME + "/" + config.getBranch();
    }

    private Path worktreePath(String configuredPath) {
        Path path = worktree.resolve(configuredPath).normalize();
        if (!path.startsWith(worktree)) {
            throw new IllegalArgumentException("ACL file escapes remote ACL worktree: " + configuredPath);
        }
        return path;
    }

    private void validateSnapshotPaths(AccessControlSnapshot snapshot) {
        for (String path : snapshot.files().keySet()) {
            worktreePath(path);
        }
    }

    private void linkHeadToBranch(Repository repository) throws IOException {
        RefUpdate.Result result = repository.updateRef(Constants.HEAD, true).link(localBranchRef());
        switch (result) {
            case NEW, FORCED, NO_CHANGE -> {
            }
            default -> throw new IOException("Cannot prepare branch " + config.getBranch() + ": " + result);
        }
    }

    private static String gitPath(String configuredPath) {
        return configuredPath.replace('\\', '/');
    }

    private CloneCommand configure(CloneCommand command) {
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        if (transportConfigCallback != null) {
            command.setTransportConfigCallback(transportConfigCallback);
        }
        return command;
    }

    private FetchCommand configure(FetchCommand command) {
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        if (transportConfigCallback != null) {
            command.setTransportConfigCallback(transportConfigCallback);
        }
        return command;
    }

    private PushCommand configure(PushCommand command) {
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        if (transportConfigCallback != null) {
            command.setTransportConfigCallback(transportConfigCallback);
        }
        return command;
    }

    private static CredentialsProvider credentialsProvider(Map<String, String> auth) {
        if (auth == null || (!auth.containsKey("username") && !auth.containsKey("password"))) {
            return null;
        }
        String username = auth.getOrDefault("username", "");
        String password = resolveSecret("auth.password", auth.get("password"));
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    private static TransportConfigCallback transportConfigCallback(Map<String, String> auth, Path worktree) {
        if (auth == null || !auth.containsKey("privateKey")) {
            return null;
        }
        Path privateKey = fileReference("auth.privateKey", auth.get("privateKey"));
        Path sshDirectory = worktree.resolve(".ssh").toAbsolutePath().normalize();
        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setSshDirectory(sshDirectory.toFile())
                .setDefaultIdentities(ignored -> List.of(privateKey));
        if (auth.containsKey("knownHosts")) {
            Path knownHosts = fileReference("auth.knownHosts", auth.get("knownHosts"));
            builder.setDefaultKnownHostsFiles(ignored -> List.of(knownHosts));
        }
        if (auth.containsKey("passphrase")) {
            char[] passphrase = resolveSecret("auth.passphrase", auth.get("passphrase")).toCharArray();
            builder.setKeyPasswordProvider(ignored -> new StaticKeyPasswordProvider(passphrase));
        }
        SshdSessionFactory sessionFactory = builder.build(null);
        return transport -> {
            if (transport instanceof SshTransport sshTransport) {
                sshTransport.setSshSessionFactory(sessionFactory);
            }
        };
    }

    private static Path fileReference(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        ResourceLocation location = ResourceLocation.parse(value, name);
        return switch (location.scheme()) {
            case ResourceScheme.File ignored -> Path.of(location.pathOrSchemeSpecificPart(name + " must include a path"));
            default -> throw new IllegalArgumentException(name + " must use file: reference");
        };
    }

    private static String resolveSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith("env:")) {
            String variableName = value.substring("env:".length());
            String secret = System.getenv(variableName);
            if (secret == null) {
                throw new IllegalArgumentException(name + " environment variable is not set: " + variableName);
            }
            return secret;
        }
        if (value.startsWith("file:")) {
            try {
                return Files.readString(fileReference(name, value), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + name + " from " + value, e);
            }
        }
        throw new IllegalArgumentException(name + " must use env: or file: reference");
    }

    private static Path remoteWorktree(
            OrionConfiguration configuration,
            OrionConfiguration.BootstrapAccessControlConfig config) {
        Path workDir = new ConfigurationContext(configuration).getWorkDir();
        String id = sha256(normalizeRemoteUri(config.getLocation()) + "#" + config.getBranch());
        return workDir.resolve("acl-remote").resolve(id);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String authorName(UserEmail author) {
        if (author == null || author.getUsername() == null || author.getUsername().isBlank()) {
            return "orion";
        }
        return author.getUsername();
    }

    private static String authorEmail(UserEmail author) {
        if (author == null || author.getEmail() == null || author.getEmail().isBlank()) {
            return "orion@localhost";
        }
        return author.getEmail();
    }

    private static final class StaticKeyPasswordProvider implements KeyPasswordProvider {
        private final char[] passphrase;
        private int attempts;

        private StaticKeyPasswordProvider(char[] passphrase) {
            this.passphrase = passphrase.clone();
        }

        @Override
        public char[] getPassphrase(org.eclipse.jgit.transport.URIish uri, int attempt) {
            return passphrase.clone();
        }

        @Override
        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        @Override
        public int getAttempts() {
            return attempts;
        }

        @Override
        public boolean keyLoaded(org.eclipse.jgit.transport.URIish uri, int attempt, Exception error)
                throws IOException, GeneralSecurityException {
            if (error != null) {
                throw new GeneralSecurityException(error);
            }
            return true;
        }
    }
}
