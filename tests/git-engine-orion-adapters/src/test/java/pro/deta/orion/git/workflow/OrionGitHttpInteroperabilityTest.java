package pro.deta.orion.git.workflow;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitSmartHttpClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.transport.http.OrionAuthorizationFilter;
import pro.deta.orion.transport.http.OrionGitRoute;
import pro.deta.orion.transport.http.OrionHttpResponseWriter;
import pro.deta.orion.transport.http.OrionHttpRouteRegistry;
import pro.deta.orion.transport.http.OrionHttpRouteServlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitHttpInteroperabilityTest {
    private static final GitCommandRunner GIT = new GitCommandRunner("git", Duration.ofSeconds(30));

    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0} -> Orion HTTP, local divergence: {1}")
    @CsvSource({"git-v1,0", "git-v2,0", "jgit-v2,0", "orion-v1,0",
            "git-v1,160", "git-v2,160", "jgit-v2,160"})
    void discoversAndFetchesInitialAndUpdatedHistory(String engine, int divergence) throws Exception {
        FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(directory.resolve("server"));
        NativeGitRepository repository = provider.create("project").valueOrFailure("repository");
        List<String> versions = new CopyOnWriteArrayList<>();
        AtomicInteger uploadRequests = new AtomicInteger();
        List<String> history = new ArrayList<>();
        OrionGitRoute route = new OrionGitRoute(new DefaultGitNativeRepositoryService(provider),
                new GitTransportConfig(), provider);
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of(route)),
                new OrionHttpResponseWriter(new ObjectMapper())) {
            @Override
            public void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException {
                if (request.getMethod().equals("POST") && request.getRequestURI().endsWith("/git-upload-pack")) {
                    uploadRequests.incrementAndGet();
                }
                versions.add(request.getHeader("Git-Protocol") == null ? "absent" : request.getHeader("Git-Protocol"));
                AccessControl.Grant grant = new AccessControlDraft.Grant("repository", new ArrayList<>())
                        .addKey(AccessControl.GrantKey.REPOSITORY, "project").toAccessControl();
                request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                        SecurityContext.createContext().withUserIdentity(
                                new InternalUserImpl("git-user", List.of(grant))));
                super.service(request, response);
            }
        };
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        Path local = directory.resolve("client");
        try (Git client = Git.init().setDirectory(local.toFile()).setInitialBranch("main").call()) {
            client.getRepository().getConfig().setString("protocol", null, "version", "2");
            server.start();
            URI remote = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/r/project.git");
            if (divergence > 0) {
                for (int number = 0; number < 64; number++) {
                    repository.saveFiles("main",
                            Map.of("README.md", GitFile.regular(("history " + number).getBytes(
                                    StandardCharsets.UTF_8))), Set.of(),
                                            "history " + number, GitCommitAuthor.EMPTY);
                    history.addFirst(repository.refs().get("refs/heads/main"));
                }
            }
            String localTip = null;
            List<String> updates = divergence > 0 ? List.of("base\n", "initial\n", "updated\n")
                    : List.of("initial\n", "updated\n");
            for (String content : updates) {
                repository.saveFiles("main",
                        Map.of("README.md", GitFile.regular(content.getBytes(StandardCharsets.UTF_8))), Set.of(),
                        "update", GitCommitAuthor.EMPTY);
                String commit = repository.refs().get("refs/heads/main");
                history.addFirst(commit);
                int requestsBeforeFetch = uploadRequests.get();
                if (engine.startsWith("git-")) {
                    String version = engine.substring(5);
                    assertThat(git(local, version, "ls-remote", remote.toString()))
                            .contains(commit + "\trefs/heads/main");
                    requestsBeforeFetch = uploadRequests.get();
                    git(local, version, "fetch", remote.toString(),
                            "refs/heads/main:refs/remotes/origin/main");
                    assertThat(git(local, version, "rev-parse", "FETCH_HEAD").strip()).isEqualTo(commit);
                    assertThat(git(local, version, "show", "FETCH_HEAD:README.md")).isEqualTo(content);
                } else if (engine.equals("jgit-v2")) {
                    client.fetch().setRemote(remote.toString())
                            .setRefSpecs(new RefSpec("refs/heads/main:refs/remotes/origin/main")).call();
                    assertThat(client.getRepository().resolve("refs/remotes/origin/main").name()).isEqualTo(commit);
                    assertThat(git(local, "2", "show", "refs/remotes/origin/main:README.md")).isEqualTo(content);
                } else {
                    fetchWithOrion(remote, commit, content);
                }
                if (divergence > 0) {
                    int requests = uploadRequests.get() - requestsBeforeFetch;
                    assertThat(git(local, "2", "rev-list", "refs/remotes/origin/main").lines().toList())
                            .containsExactlyElementsOf(history);
                    if (content.equals("initial\n")) {
                        client.checkout().setCreateBranch(true).setName("main").setStartPoint(commit).call();
                        for (int number = 0; number < divergence; number++) {
                            client.commit().setAllowEmpty(true).setMessage("local " + number)
                                    .setAuthor("Matrix", "matrix@example.test")
                                    .setCommitter("Matrix", "matrix@example.test").call();
                        }
                        localTip = client.getRepository().resolve("HEAD").name();
                        git(local, "2", "update-ref", "-d", "refs/remotes/origin/main");
                    } else if (localTip != null) {
                        assertThat(requests).as("upload-pack HTTP requests during divergent fetch")
                                .isGreaterThan(engine.endsWith("v2") ? 2 : 1);
                        assertThat(client.getRepository().resolve("HEAD").name()).isEqualTo(localTip);
                        assertThat(Files.readString(local.resolve("README.md"))).isEqualTo("initial\n");
                        git(local, "2", "fsck", "--full");
                    }
                }
            }
            assertThat(versions).contains(engine.endsWith("v2") ? "version=2" : "version=1");
        } finally {
            server.stop();
            repository.close();
        }
    }

    private void fetchWithOrion(URI remote, String commit, String content) throws Exception {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()) {
            GitUploadPackClient client = new GitUploadPackClient(
                    new GitSmartHttpClientTransport(http, GitCredentials.none(), true));
            assertThat(client.discover(remote, GitClientOptions.defaults())).isInstanceOf(GitClientResult.Success.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertThat(client.fetch(remote, GitClientOptions.defaults(),
                    GitUploadPackRequest.of(commit, new OutputStreamBufferedByteOutput(bytes))))
                    .isInstanceOf(GitClientResult.Success.class);
            FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(
                    Files.createTempDirectory(directory, "received-"));
            try (NativeGitRepository received = provider.create("copy").valueOrFailure("copy");
                 BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes.toByteArray()));
                 IndexedPack pack = received.ingest(input)) {
                received.storage().persist(pack);
                received.updateRef("refs/heads/main", "0".repeat(40), commit);
                assertThat(new String(received.loadFiles("main",
                        List.of("README.md")).files().get("README.md").content(),
                        StandardCharsets.UTF_8)).isEqualTo(content);
            }
        }
    }

    private static String git(Path local, String version, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("-c", "protocol.version=" + version,
                "-c", "fetch.negotiationAlgorithm=consecutive"));
        command.addAll(List.of(arguments));
        return GIT.run(local, command.toArray(String[]::new)).output();
    }
}
