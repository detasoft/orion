package pro.deta.orion.git.client;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.Daemon;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;

final class JGitDaemonTestServer implements AutoCloseable {
    private final Path repositoryPath;
    private final Daemon daemon;

    private JGitDaemonTestServer(Path repositoryPath, Daemon daemon) {
        this.repositoryPath = repositoryPath;
        this.daemon = daemon;
    }

    static JGitDaemonTestServer start(Path repositoryPath) throws IOException {
        Path checkedPath = repositoryPath.toAbsolutePath().normalize();
        Daemon daemon = new Daemon(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0));
        JGitDaemonTestServer server = new JGitDaemonTestServer(
                checkedPath, daemon);
        daemon.setRepositoryResolver((client, name) ->
                server.openRepository(name));
        daemon.getService("receive-pack").setEnabled(true);
        daemon.start();
        return server;
    }

    URI repositoryUri() {
        InetSocketAddress address = daemon.getAddress();
        return URI.create("git://127.0.0.1:"
                + address.getPort() + "/test.git");
    }

    private Repository openRepository(String name)
            throws RepositoryNotFoundException {
        if (!"test.git".equals(name)) {
            throw new RepositoryNotFoundException(name);
        }
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(repositoryPath.toFile())
                    .build();
        } catch (IOException error) {
            throw new RepositoryNotFoundException(name, error);
        }
    }

    @Override
    public void close() throws InterruptedException {
        daemon.stopAndWait();
    }

}
