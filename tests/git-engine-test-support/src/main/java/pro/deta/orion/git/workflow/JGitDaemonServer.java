package pro.deta.orion.git.workflow;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.Daemon;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class JGitDaemonServer implements GitServer {
    private final Daemon daemon;
    private final Map<String, Path> repositories = new ConcurrentHashMap<>();

    private JGitDaemonServer(Daemon daemon) {
        this.daemon = daemon;
    }

    static JGitDaemonServer start() throws IOException {
        Daemon daemon = new Daemon(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        JGitDaemonServer server = new JGitDaemonServer(daemon);
        daemon.setRepositoryResolver((client, name) -> server.openRepository(name));
        daemon.getService("receive-pack").setEnabled(true);
        daemon.start();
        return server;
    }

    @Override
    public String name() {
        return "jgit";
    }

    @Override
    public Set<GitCapability> capabilities() {
        return GitCapability.all();
    }

    @Override
    public String diagnostics() {
        return JGitDiagnostics.version();
    }

    @Override
    public GitRemoteRepository createRemoteRepository(Path directory, String repositoryName) throws IOException {
        requireRepositoryName(repositoryName);
        Path root = directory.toAbsolutePath().normalize();
        Path repositoryPath = root.resolve(repositoryName).normalize();
        if (!repositoryPath.getParent().equals(root)) {
            throw new IllegalArgumentException("Repository must stay inside the server root: " + repositoryName);
        }
        GitRemoteRepository.createBare(repositoryPath);
        if (repositories.putIfAbsent(repositoryName, repositoryPath) != null) {
            throw new IllegalStateException("Repository already provisioned: " + repositoryName);
        }
        InetSocketAddress address = daemon.getAddress();
        URI uri = URI.create("git://127.0.0.1:" + address.getPort() + "/" + repositoryName);
        return new GitRemoteRepository(repositoryPath, uri.toString());
    }

    private Repository openRepository(String name) throws RepositoryNotFoundException {
        Path repositoryPath = repositories.get(name);
        if (repositoryPath == null) {
            throw new RepositoryNotFoundException(name);
        }
        try {
            return new FileRepositoryBuilder().setGitDir(repositoryPath.toFile()).build();
        } catch (IOException error) {
            throw new RepositoryNotFoundException(name, error);
        }
    }

    @Override
    public void close() throws InterruptedException {
        daemon.stopAndWait();
    }

    private static void requireRepositoryName(String repositoryName) {
        if (repositoryName.isBlank()
                || ".".equals(repositoryName)
                || "..".equals(repositoryName)
                || repositoryName.contains("/")
                || repositoryName.contains("\\")) {
            throw new IllegalArgumentException("Repository name must be one path segment: " + repositoryName);
        }
    }
}
