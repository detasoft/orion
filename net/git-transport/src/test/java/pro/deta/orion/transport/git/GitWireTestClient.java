package pro.deta.orion.transport.git;

import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsRequest;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class GitWireTestClient {
    static final String NULL_ID = "0".repeat(40);
    static final String MAIN_ID = "88d050b1908057b53d38b42702ebc659e3d7f696";
    static final String TAG_ID = "35de90dbe54c04e6c4b7a36160ac57be632a1b52";

    private GitWireTestClient() {}

    static NativeGitRepository createRepository(NativeGitRepositoryProvider provider, String name) {
        NativeGitRepository repository = provider.create(name).valueOrFailure("repository");
        assertThat(repository.writeObject(GitObjectType.BLOB, "main".getBytes(StandardCharsets.US_ASCII)).toHex())
                .isEqualTo(MAIN_ID);
        assertThat(repository.writeObject(GitObjectType.BLOB, "tag".getBytes(StandardCharsets.US_ASCII)).toHex())
                .isEqualTo(TAG_ID);
        return repository;
    }

    static InMemoryNativeGitRepositoryProvider providerWithMainRef() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        createRepository(provider, "demo").updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        return provider;
    }

    static InitialRequestData request(String path) {
        return new InitialRequestData(InitialRequestService.UPLOAD_PACK, path, "localhost", Map.of());
    }

    static InitialRequestData receiveRequest(String path) {
        return new InitialRequestData(InitialRequestService.RECEIVE_PACK, path, "localhost", Map.of());
    }

    static GitV1Advertisement legacyUploadPackAdvertisement(DefaultGitNativeRepositoryService service,
            InitialRequestData data) throws IOException {
        return advertise(service, data, GitWireConfiguration.allSupported());
    }

    static GitV1Advertisement legacyReceivePackAdvertisement(DefaultGitNativeRepositoryService service,
            InitialRequestData data) throws IOException {
        return advertise(service, data, GitWireConfiguration.allSupported());
    }

    static GitV1Advertisement advertise(DefaultGitNativeRepositoryService service, InitialRequestData data,
            GitWireConfiguration configuration) throws IOException {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
        new GitBlockingWireSession(request -> service.open(request, GitNativeRepositoryAccessHook.ALLOW_ALL),
                configuration, new GitBlockingWireTransport(output)).advertise(data);
        GitCapabilities capabilities = new GitCapabilities();
        List<GitAdvertisedRef> refs = new ArrayList<>();
        for (String line : lines(output.bytes())) {
            int nul = line.indexOf('\0');
            if (nul >= 0) {
                for (String token : line.substring(nul + 1).split(" ")) {
                    capabilities.add(GitCapabilityValue.parse(token, GitHashAlgorithm.SHA1));
                }
                line = line.substring(0, nul);
            }
            int separator = line.indexOf(' ');
            refs.add(GitAdvertisedRef.direct(line.substring(0, separator), line.substring(separator + 1)));
        }
        return new GitV1Advertisement(capabilities, refs);
    }

    static GitLsRefsResponse lsRefs(DefaultGitNativeRepositoryService service, InitialRequestData data,
            LsRefsRequest request) throws IOException {
        List<String> arguments = new ArrayList<>();
        if (request.peel()) {
            arguments.add("peel");
        }
        if (request.symrefs()) {
            arguments.add("symrefs");
        }
        if (request.unborn()) {
            arguments.add("unborn");
        }
        for (String prefix : request.refPrefixes()) {
            arguments.add("ref-prefix " + prefix);
        }
        List<GitLsRefsResponse.Ref> refs = new ArrayList<>();
        for (String line : executeV2(service, data.repositoryPath(), "ls-refs", arguments)) {
            String[] fields = line.split(" ");
            Optional<String> symref = Optional.empty();
            Optional<String> peeled = Optional.empty();
            for (int index = 2; index < fields.length; index++) {
                if (fields[index].startsWith("symref-target:")) {
                    symref = Optional.of(fields[index].substring("symref-target:".length()));
                } else if (fields[index].startsWith("peeled:")) {
                    peeled = Optional.of(fields[index].substring("peeled:".length()));
                }
            }
            refs.add(fields[0].equals("unborn")
                    ? new GitLsRefsResponse.UnbornRef(fields[1], symref.orElseThrow())
                    : direct(fields[0], fields[1], symref, peeled));
        }
        return new GitLsRefsResponse(refs);
    }

    static List<String> executeV2(DefaultGitNativeRepositoryService service, String repository,
            String command, List<String> arguments) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        packet(request, "command=" + command);
        request.writeBytes("0001".getBytes(StandardCharsets.US_ASCII));
        for (String argument : arguments) {
            packet(request, argument);
        }
        request.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(request.toByteArray()))) {
            new GitBlockingWireSession(data -> service.open(data, GitNativeRepositoryAccessHook.ALLOW_ALL),
                    GitWireConfiguration.allSupported(), new GitBlockingWireTransport(input, output))
                    .serveSmartHttpPost(new InitialRequestData(InitialRequestService.UPLOAD_PACK,
                            repository, "localhost", Map.of("version", "2")));
        }
        return lines(output.bytes());
    }

    static List<String> lines(byte[] bytes) throws IOException {
        List<String> result = new ArrayList<>();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            Optional<GitPktLine> packet;
            while ((packet = GitPktLine.readNextFrom(input)).isPresent()) {
                if (packet.orElseThrow() instanceof GitPktLine.Data data) {
                    String line = new String(data.content(), StandardCharsets.UTF_8);
                    result.add(line.endsWith("\n") ? line.substring(0, line.length() - 1) : line);
                }
            }
        }
        return result;
    }

    static void packet(ByteArrayOutputStream output, String text) {
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        output.writeBytes(String.format("%04x", bytes.length + 4).getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(bytes);
    }

    static GitLsRefsResponse.DirectRef direct(String objectId, String name) {
        return direct(objectId, name, Optional.empty(), Optional.empty());
    }

    static GitLsRefsResponse.DirectRef direct(String objectId, String name, Optional<String> symref,
            Optional<String> peeled) {
        return new GitLsRefsResponse.DirectRef(objectId, name, symref, peeled);
    }

    static GitWireConfiguration uploadConfiguration(boolean multiAckDetailed, boolean thinPack, boolean sideBand64k,
            boolean ofsDelta, boolean symref, boolean agent) {
        GitWireConfiguration supported = GitWireConfiguration.allSupported();
        return new GitWireConfiguration(new GitWireConfiguration.LegacyUploadPack(
                multiAckDetailed, thinPack, sideBand64k, ofsDelta, symref, agent),
                supported.receivePack(), supported.protocolV2());
    }

    static GitWireConfiguration receiveConfiguration(boolean reportStatus, boolean sideBand64k, boolean ofsDelta,
            boolean objectFormat, boolean agent) {
        GitWireConfiguration supported = GitWireConfiguration.allSupported();
        return new GitWireConfiguration(supported.uploadPack(), new GitWireConfiguration.LegacyReceivePack(
                reportStatus, sideBand64k, ofsDelta, objectFormat, agent), supported.protocolV2());
    }

    static List<String> capabilityTokens(GitV1Advertisement advertisement) {
        List<String> tokens = new ArrayList<>();
        for (GitCapabilityValue capability : advertisement.capabilities()) {
            tokens.add(capability.wireToken());
        }
        return tokens;
    }
}
