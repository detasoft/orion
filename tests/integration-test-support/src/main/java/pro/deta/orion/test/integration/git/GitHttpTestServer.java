package pro.deta.orion.test.integration.git;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public final class GitHttpTestServer implements AutoCloseable {
    private final Path repositoriesRoot;
    private final Server server;
    private final ServerConnector connector;

    private GitHttpTestServer(Path repositoriesRoot, Server server, ServerConnector connector) {
        this.repositoriesRoot = repositoriesRoot.toAbsolutePath().normalize();
        this.server = server;
        this.connector = connector;
    }

    public static GitHttpTestServer start(Path repositoriesRoot) throws Exception {
        Files.createDirectories(repositoriesRoot);

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("localhost");
        connector.setPort(0);
        server.addConnector(connector);

        GitHttpTestServer testServer = new GitHttpTestServer(repositoriesRoot, server, connector);
        GitServlet gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver((request, repositoryName) -> testServer.openRepository(repositoryName));

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(gitServlet), "/*");
        server.setHandler(context);
        server.start();
        return testServer;
    }

    public String repositoryUrl(String repositoryName) {
        return "http://localhost:" + connector.getLocalPort() + "/" + repositoryName;
    }

    private Repository openRepository(String repositoryName) throws RepositoryNotFoundException {
        Path repositoryPath = repositoriesRoot.resolve(repositoryName).normalize();
        if (!repositoryPath.startsWith(repositoriesRoot) || !Files.exists(repositoryPath)) {
            throw new RepositoryNotFoundException(repositoryName);
        }
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(repositoryPath.toFile())
                    .build();
        } catch (java.io.IOException e) {
            throw new RepositoryNotFoundException(repositoryName, e);
        }
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }
}
