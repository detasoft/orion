package pro.deta.orion.git;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.Daemon;
import org.eclipse.jgit.transport.DaemonClient;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public final class TestDFSRepository {
    private static final int GIT_PORT = 9418;
    private static final Map<String, InMemoryRepository> repositories = new HashMap<>();

    private TestDFSRepository() {
    }

    private static final class RepositoryResolverImplementation implements RepositoryResolver<DaemonClient> {
        @Override
        public Repository open(DaemonClient client, String name) {
            return repositories.computeIfAbsent(name, repositoryName ->
                    new InMemoryRepository(new DfsRepositoryDescription(repositoryName)));
        }
    }

    public static void main(String[] args) throws IOException {
        Daemon server = new Daemon(new InetSocketAddress(GIT_PORT));
        boolean uploadsEnabled = true;
        server.getService("git-receive-pack").setEnabled(uploadsEnabled);
        server.setRepositoryResolver(new RepositoryResolverImplementation());
        server.start();
    }
}
