package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWireBootstrapTest {

    @Test
    void canonicalizesEquivalentRepositoryPathsAtEveryWireIngress() throws Exception {
        GitWireBootstrap smartHttp = smartHttp("/team%2Frepo.git");
        InitialRequestData ssh = GitWireBootstrap.sshCommandData(
                "git-upload-pack '/team%5Crepo.git'",
                null);
        GitWireBootstrap daemon = nativeDaemonPayload("git-upload-pack /team/repo.git");

        assertThat(smartHttp.data().repositoryPath()).isEqualTo("team/repo");
        assertThat(ssh.repositoryPath()).isEqualTo("team/repo");
        assertThat(daemon.data().repositoryPath()).isEqualTo("team/repo");
    }

    @Test
    void rejectsInvalidRepositoryPathsAtWireIngress() {
        for (String path : new String[]{
                "/", "//repo.git", "/../repo.git", "/%2E%2E/repo.git", "/repo%252Egit",
                "/repo%GG.git", "/Repo.git", "/r%C3%A9po.git"}) {
            assertThatThrownBy(() -> smartHttp(path))
                    .as("Git repository path %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsNativeGitDaemonInitialRequest() throws Exception {
        GitWireBootstrap bootstrap = nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=localhost\0\0version=2\0");

        InitialRequestData data = bootstrap.data();

        assertThat(bootstrap.wire()).isNotNull();
        assertThat(data.service()).isEqualTo(InitialRequestService.UPLOAD_PACK);
        assertThat(data.repositoryPath()).isEqualTo("repo");
        assertThat(data.host()).isEqualTo("localhost");
        assertThat(data.getProtocolVersion())
                .contains(GitProtocolVersion.V2);
    }

    @Test
    void nativeDaemonSelectsHighestRecognizedProtocolVersion() throws Exception {
        GitWireBootstrap bootstrap = nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=localhost\0\0"
                        + "version=2\0version=9\0version=1\0");

        assertThat(bootstrap.data().getProtocolVersion())
                .contains(GitProtocolVersion.V2);
        assertThat(bootstrap.data().protocolParameters())
                .containsExactly("version=2", "version=9", "version=1");
    }

    @Test
    void acceptsNativeGitDaemonRequestWithoutMetadataSeparator() throws Exception {
        InitialRequestData data = nativeDaemonPayload(
                "git-upload-pack /repo.git").data();

        assertThat(data.host()).isNull();
        assertThat(data.protocolParameters()).isEmpty();
    }

    @Test
    void acceptsMixedCaseNativeGitDaemonHostAndTrailingNewline() throws Exception {
        InitialRequestData data = nativeDaemonPayload(
                "git-upload-pack /repo.git\n\0HoSt=Git.Example:9418\0").data();

        assertThat(data.host()).isEqualTo("git.example");
        assertThat(data.protocolParameters()).isEmpty();
    }

    @Test
    void acceptsEmptyNativeGitDaemonHost() throws Exception {
        InitialRequestData data = nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=\0").data();

        assertThat(data.host()).isEmpty();
    }

    @Test
    void rejectsNativeGitDaemonProtocolParameterInHostPosition() {
        assertThatThrownBy(() -> nativeDaemonPayload(
                "git-upload-pack /repo.git\0version=2\0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed native Git request");
    }

    @Test
    void rejectsNativeGitDaemonProtocolParameterWithoutSecondNull() {
        assertThatThrownBy(() -> nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=localhost\0version=2\0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed native Git request");
    }

    @Test
    void createsSmartHttpBootstrap() {
        GitWireBootstrap bootstrap = GitWireBootstrap.smartHttp(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(new byte[0])),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                InitialRequestService.RECEIVE_PACK,
                "/team/project.git",
                "localhost",
                "version=2:agent=ignored");

        assertThat(bootstrap.wire()).isNotNull();
        assertThat(bootstrap.data().service())
                .isEqualTo(InitialRequestService.RECEIVE_PACK);
        assertThat(bootstrap.data().repositoryPath()).isEqualTo("team/project");
        assertThat(bootstrap.data().host()).isEqualTo("localhost");
        assertThat(bootstrap.data().parameters()).isEqualTo(Map.of("version", "2"));
    }

    @Test
    void smartHttpSelectsHighestRecognizedProtocolVersion() {
        GitWireBootstrap bootstrap = GitWireBootstrap.smartHttp(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(new byte[0])),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                InitialRequestService.UPLOAD_PACK,
                "/team/project.git",
                "localhost",
                "version=0:version=9:version=2:version=1");

        assertThat(bootstrap.data().getProtocolVersion())
                .contains(GitProtocolVersion.V2);
    }

    @Test
    void smartHttpAcceptsExplicitProtocolV0AndIgnoresUnknownOffers() {
        GitWireBootstrap explicitV0 = GitWireBootstrap.smartHttp(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(new byte[0])),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                InitialRequestService.UPLOAD_PACK,
                "/team/project.git",
                "localhost",
                "version=9:version=0");
        GitWireBootstrap unknownOnly = GitWireBootstrap.smartHttp(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(new byte[0])),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                InitialRequestService.UPLOAD_PACK,
                "/team/project.git",
                "localhost",
                "version=9");

        assertThat(explicitV0.data().getProtocolVersion())
                .contains(GitProtocolVersion.V0);
        assertThat(unknownOnly.data().getProtocolVersion()).isEmpty();
    }

    @Test
    void createsSshCommandBootstrapData() {
        InitialRequestData data = GitWireBootstrap.sshCommandData(
                "git-upload-pack '/team/project.git'",
                "version=2");

        assertThat(data.service()).isEqualTo(InitialRequestService.UPLOAD_PACK);
        assertThat(data.repositoryPath()).isEqualTo("team/project");
        assertThat(data.host()).isNull();
        assertThat(data.getProtocolVersion())
                .contains(GitProtocolVersion.V2);
    }

    private static GitWireBootstrap nativeDaemon(String ascii) throws Exception {
        return GitWireBootstrap.nativeDaemon(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(
                                ascii.getBytes(StandardCharsets.US_ASCII))),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()));
    }

    private static GitWireBootstrap smartHttp(String repositoryPath) {
        return GitWireBootstrap.smartHttp(
                new InputStreamBufferedByteInput(new ByteArrayInputStream(new byte[0])),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                InitialRequestService.UPLOAD_PACK,
                repositoryPath,
                "localhost",
                null);
    }

    private static GitWireBootstrap nativeDaemonPayload(String payload) throws Exception {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String header = "%04x".formatted(payloadBytes.length + 4);
        return nativeDaemon(header + payload);
    }
}
