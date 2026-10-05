package pro.deta.orion.transport.http;

import pro.deta.orion.git.s3.S3NativeGitRepositoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.bootstrap.config.GitTransportConfig;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HexFormat;
import java.util.Random;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(180)
class S3GitTransportIT {
    @TempDir
    Path directory;

    @Test
    void pushesClonesAndPushesADeltaThenClonesThroughAFreshS3Provider() throws Exception {
        byte[] content = new byte[32000];
        new Random(1).nextBytes(content);
        String original = HexFormat.of().formatHex(content);
        String updated = original + "changed tail\n";
        try (MinioS3TestServer minio = MinioS3TestServer.start("orion-git-" + UUID.randomUUID());
             Git source = Git.init().setDirectory(directory.resolve("source").toFile())
                     .setInitialBranch("main").call()) {
            Files.writeString(directory.resolve("source/file.txt"), original);
            source.add().addFilepattern("file.txt").call();
            source.commit().setMessage("first").setAuthor("Tester", "test@example.com").call();
            try (NativeGitRepositoryProvider provider = provider(minio); Http server = new Http(provider)) {
                provider.create("repo").valueOrFailure("create");
                push(source, server.url());
                try (Git clone = Git.cloneRepository().setURI(server.url())
                        .setDirectory(directory.resolve("clone").toFile()).call()) {
                    assertThat(Files.readString(directory.resolve("clone/file.txt"))).isEqualTo(original);
                    Files.writeString(directory.resolve("clone/file.txt"), updated);
                    clone.add().addFilepattern("file.txt").call();
                    clone.commit().setMessage("delta").setAuthor("Tester", "test@example.com").call();
                    push(clone, server.url());
                }
                try (NativeGitRepository repository =
                             provider.find("repo").valueOrFailure("inspect delta")) {
                    repository.index().withAccess(index -> {
                        boolean delta = false;
                        for (PackMetadata pack : index.packs()) {
                            for (IndexedObject object : index.objects(pack.packId())) {
                                delta |= object.delta().isPresent();
                            }
                        }
                        assertThat(delta).isTrue();
                        return null;
                    });
                }
            }
            try (NativeGitRepositoryProvider reopened = provider(minio); Http server = new Http(reopened);
                 Git clone = Git.cloneRepository().setURI(server.url())
                         .setDirectory(directory.resolve("reopened").toFile()).call()) {
                assertThat(Files.readString(directory.resolve("reopened/file.txt"))).isEqualTo(updated);
                assertThat(clone.log().call()).hasSize(2);
            }
        }
    }

    private static void push(Git git, String url) throws Exception {
        Iterable<PushResult> results = git.push().setRemote(url).setThin(true)
                .setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        assertThat(results).hasSize(1);
        for (PushResult result : results) {
            assertThat(result.getRemoteUpdates()).extracting(RemoteRefUpdate::getStatus)
                    .containsExactly(RemoteRefUpdate.Status.OK);
        }
    }

    private static NativeGitRepositoryProvider provider(MinioS3TestServer server) {
        return S3NativeGitRepositoryFactory.repositories("s3://" + server.bucketName() + "/repos", server.endpoint(),
                Map.of("accessKeyId", server.accessKeyId(), "secretAccessKey", "env:SECRET"),
                Map.of("SECRET", server.secretAccessKey()));
    }

    private static final class Http implements AutoCloseable {
        private final Server server = new Server();
        private final ServerConnector connector = new ServerConnector(server);

        Http(NativeGitRepositoryProvider provider) throws Exception {
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            Grant grant = new Grant("test", List.of(
                    new GrantExpression(GrantKey.REPOSITORY, "*"),
                    new GrantExpression(GrantKey.READ_WRITE, "true"),
                    new GrantExpression(GrantKey.CREATE, "true")));
            SecurityContext actor = SecurityContext.createContext()
                    .withUserIdentity(new InternalUserImpl("tester", List.of(grant)));
            OrionGitRoute route = new OrionGitRoute(new DefaultGitNativeRepositoryService(provider),
                    new GitTransportConfig(), provider);
            OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of(route)),
                    new OrionHttpResponseWriter(new ObjectMapper())) {
                @Override
                public void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException {
                    request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE, actor);
                    super.service(request, response);
                }
            };
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.addServlet(new ServletHolder(servlet), "/*");
            server.setHandler(context);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + connector.getLocalPort() + "/r/repo.git"; }

        public void close() throws Exception { server.stop(); }
    }
}
