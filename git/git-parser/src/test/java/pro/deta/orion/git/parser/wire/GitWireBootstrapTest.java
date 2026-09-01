package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;
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
    void readsNativeGitDaemonInitialRequest() throws Exception {
        GitWireBootstrap bootstrap = nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=localhost\0\0version=2\0");

        InitialRequestData data = bootstrap.data();

        assertThat(bootstrap.wire()).isNotNull();
        assertThat(data.getService()).isEqualTo(InitialRequestService.UPLOAD_PACK);
        assertThat(data.getRepositoryPath()).isEqualTo("repo");
        assertThat(data.getHost()).isEqualTo("localhost");
        assertThat(data.getProtocolVersion())
                .contains(InitialRequestData.ProtocolVersion.V2);
    }

    @Test
    void nativeDaemonSelectsHighestRecognizedProtocolVersion() throws Exception {
        GitWireBootstrap bootstrap = nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=localhost\0\0"
                        + "version=2\0version=9\0version=1\0");

        assertThat(bootstrap.data().getProtocolVersion())
                .contains(InitialRequestData.ProtocolVersion.V2);
        assertThat(bootstrap.data().getProtocolParameters())
                .containsExactly("version=2", "version=9", "version=1");
    }

    @Test
    void acceptsNativeGitDaemonRequestWithoutMetadataSeparator() throws Exception {
        InitialRequestData data = nativeDaemonPayload(
                "git-upload-pack /repo.git").data();

        assertThat(data.getHost()).isNull();
        assertThat(data.getProtocolParameters()).isEmpty();
    }

    @Test
    void acceptsMixedCaseNativeGitDaemonHostAndTrailingNewline() throws Exception {
        InitialRequestData data = nativeDaemonPayload(
                "git-upload-pack /repo.git\n\0HoSt=Git.Example:9418\0").data();

        assertThat(data.getHost()).isEqualTo("git.example");
        assertThat(data.getProtocolParameters()).isEmpty();
    }

    @Test
    void acceptsEmptyNativeGitDaemonHost() throws Exception {
        InitialRequestData data = nativeDaemonPayload(
                "git-upload-pack /repo.git\0host=\0").data();

        assertThat(data.getHost()).isEmpty();
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
        assertThat(bootstrap.data().getService())
                .isEqualTo(InitialRequestService.RECEIVE_PACK);
        assertThat(bootstrap.data().getRepositoryPath()).isEqualTo("team/project");
        assertThat(bootstrap.data().getHost()).isEqualTo("localhost");
        assertThat(bootstrap.data().getParameters()).isEqualTo(Map.of("version", "2"));
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
                .contains(InitialRequestData.ProtocolVersion.V2);
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
                .contains(InitialRequestData.ProtocolVersion.V0);
        assertThat(unknownOnly.data().getProtocolVersion()).isEmpty();
    }

    @Test
    void createsSshCommandBootstrapData() {
        InitialRequestData data = GitWireBootstrap.sshCommandData(
                "git-upload-pack '/team/project.git'",
                "version=2");

        assertThat(data.getService()).isEqualTo(InitialRequestService.UPLOAD_PACK);
        assertThat(data.getRepositoryPath()).isEqualTo("team/project");
        assertThat(data.getHost()).isNull();
        assertThat(data.getProtocolVersion())
                .contains(InitialRequestData.ProtocolVersion.V2);
    }

    private static GitWireBootstrap nativeDaemon(String ascii) throws Exception {
        return GitWireBootstrap.nativeDaemon(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(
                                ascii.getBytes(StandardCharsets.US_ASCII))),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()));
    }

    private static GitWireBootstrap nativeDaemonPayload(String payload) throws Exception {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String header = "%04x".formatted(payloadBytes.length + 4);
        return nativeDaemon(header + payload);
    }
}
