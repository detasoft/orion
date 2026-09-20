package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.command.FetchCommand;
import pro.deta.orion.git.parser.v2.command.PushCommand;
import pro.deta.orion.git.parser.v2.command.RefsCommand;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsArgument;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GitBlockingWireSession {
    public static final int DEFAULT_INPUT_BUFFER_SIZE = 16 * 1024;
    private final GitNativeRepositoryService repositories;
    private final GitWireConfiguration configuration;
    private final GitBlockingWireTransport wire;

    public GitBlockingWireSession(GitNativeRepositoryService repositories, GitWireConfiguration configuration,
                                  GitBlockingWireTransport wire) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    public void advertise(InitialRequestData request) throws IOException {
        advertise(request, repositories.open(request));
    }

    public void serveCommand(InitialRequestData request) throws IOException {
        GitRepositoryContext repository = repositories.open(request);
        advertise(request, repository);
        serveRequest(request, repository, GitTransport.SSH);
    }

    public void serveSmartHttpPost(InitialRequestData request) throws IOException {
        serveRequest(request, repositories.open(request), GitTransport.HTTP);
    }

    private void advertise(InitialRequestData request, GitRepositoryContext repository) throws IOException {
        GitProtocolVersion version = version(request);
        GitCapabilities capabilities = capabilities(request);
        if (version == GitProtocolVersion.V2) {
            wire.writeTextLine("version 2");
            if (configuration.protocolV2().lsRefs()) {
                for (GitCapabilityValue value : capabilities) {
                    if (value.capability().orElse(null) == GitCapability.LS_REFS) {
                        wire.writeTextLine(value.wireToken());
                    }
                }
            }
            if (configuration.protocolV2().fetch()) {
                List<String> features = new ArrayList<>();
                for (GitCapabilityValue value : capabilities) {
                    if (value.capability().orElse(null) != GitCapability.LS_REFS) {
                        features.add(value.wireToken());
                    }
                }
                wire.writeTextLine(features.isEmpty() ? "fetch" : "fetch=" + String.join(" ", features));
            }
            wire.writeTextLine(GitCapabilityValue.value(GitCapability.OBJECT_FORMAT,
                    GitHashAlgorithm.SHA1.wireName()).wireToken());
            wire.writeTextLine(GitCapabilityValue.value(GitCapability.AGENT, "orion-native").wireToken());
            if (configuration.protocolV2().serverOption()) {
                wire.writeTextLine("server-option");
            }
            wire.writeFlush();
        } else {
            if (version == GitProtocolVersion.V1) {
                wire.writeTextLine("version 1");
            }
            wire.sendAdvertisement(legacyAdvertisement(repository, capabilities,
                    request.service() == InitialRequestService.UPLOAD_PACK && configuration.uploadPack().symref()));
        }
        wire.flush();
    }

    private void serveRequest(InitialRequestData request, GitRepositoryContext repository, GitTransport transport)
            throws IOException {
        GitProtocolContext protocol = wire.protocolContext(version(request), transport);
        GitCapabilities capabilities = capabilities(request);
        if (request.service() == InitialRequestService.RECEIVE_PACK) {
            new PushCommand(repository, capabilities).action(protocol);
        } else if (protocol.version() == GitProtocolVersion.V2) {
            serveV2(repository, protocol, capabilities);
        } else {
            new FetchCommand(repository, capabilities).action(protocol);
        }
    }

    private void serveV2(GitRepositoryContext repository, GitProtocolContext protocol, GitCapabilities capabilities)
            throws IOException {
        for (;;) {
            Optional<GitPktLine> next = wire.readNextPacket();
            if (next.isEmpty() || next.orElseThrow() == GitPktLine.Control.FLUSH) {
                return;
            }
            String command = text(next.orElseThrow());
            if (!command.equals("command=fetch") && !command.equals("command=ls-refs")) {
                throw new IOException("Unsupported protocol v2 command");
            }
            Set<String> seen = new HashSet<>();
            for (;;) {
                GitPktLine packet = wire.readNextPacket()
                        .orElseThrow(() -> new EOFException("Incomplete protocol v2 command"));
                if (packet == GitPktLine.Control.DELIMITER) {
                    break;
                }
                GitCapabilityValue value = GitCapabilityValue.parse(text(packet), GitHashAlgorithm.SHA1);
                if (value.name().equals("server-option") && configuration.protocolV2().serverOption()
                        && value.value().isPresent()) {
                    continue;
                }
                GitCapability capability = value.capability().orElse(null);
                if ((capability != GitCapability.AGENT && capability != GitCapability.OBJECT_FORMAT)
                        || value.value().isEmpty() || !seen.add(value.name())) {
                    throw new IOException("Unsupported protocol v2 command capability: " + value.name());
                }
            }
            if (command.equals("command=fetch") && configuration.protocolV2().fetch()) {
                new FetchCommand(repository, capabilities).action(protocol);
            } else if (command.equals("command=ls-refs") && configuration.protocolV2().lsRefs()) {
                new RefsCommand(repository.storage(), capabilities).action(protocol);
            } else {
                throw new IOException("Protocol v2 command was not advertised");
            }
            if (protocol.transport() == GitTransport.HTTP) {
                wire.writeResponseEnd();
                wire.flush();
                return;
            }
        }
    }

    private static String text(GitPktLine packet) throws IOException {
        if (!(packet instanceof GitPktLine.Data data)) {
            throw new IOException("Expected protocol v2 command data");
        }
        String text = data.text();
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private static GitProtocolVersion version(InitialRequestData request) {
        GitProtocolVersion version = request.getProtocolVersion().orElse(GitProtocolVersion.V0);
        return request.service() == InitialRequestService.RECEIVE_PACK && version == GitProtocolVersion.V2
                ? GitProtocolVersion.V0 : version;
    }

    private GitCapabilities capabilities(InitialRequestData request) {
        if (request.service() == InitialRequestService.RECEIVE_PACK) {
            return receivePackCapabilities(configuration);
        }
        if (version(request) != GitProtocolVersion.V2) {
            return uploadPackCapabilities(configuration);
        }
        GitCapabilities values = new GitCapabilities();
        GitWireConfiguration.ProtocolV2 v2 = configuration.protocolV2();
        if (v2.lsRefs()) {
            values.add(v2.lsRefsUnborn()
                    ? GitCapabilityValue.value(GitCapability.LS_REFS, LsRefsArgument.UNBORN.wireName())
                    : GitCapabilityValue.value(GitCapability.LS_REFS));
        }
        if (v2.shallow()) {
            values.add(GitCapabilityValue.value(GitCapability.SHALLOW));
        }
        if (v2.packfileUris()) {
            values.add(GitCapabilityValue.value(GitCapability.PACKFILE_URIS));
        }
        if (v2.filter()) {
            values.add(GitCapabilityValue.value(GitCapability.FILTER));
        }
        if (v2.waitForDone()) {
            values.add(GitCapabilityValue.value(GitCapability.WAIT_FOR_DONE));
        }
        if (v2.refInWant()) {
            values.add(GitCapabilityValue.value(GitCapability.REF_IN_WANT));
        }
        if (v2.sidebandAll()) {
            values.add(GitCapabilityValue.value(GitCapability.SIDEBAND_ALL));
        }
        return values;
    }

    private static GitV1Advertisement legacyAdvertisement(GitRepositoryContext repository,
            GitCapabilities capabilities, boolean advertiseSymref) throws IOException {
        RefsSnapshot snapshot = repository.storage().snapshotRefs();
        List<GitAdvertisedRef> refs = new ArrayList<>();
        ObjectId head = snapshot.head() instanceof Head.Symbolic symbolic
                ? snapshot.refs().get(symbolic.target())
                : new ObjectId(((Head.Detached) snapshot.head()).target().toBytes());
        if (head != null) {
            refs.add(GitAdvertisedRef.direct(head.toHex(), "HEAD"));
        }
        if (advertiseSymref && snapshot.head() instanceof Head.Symbolic symbolic) {
            capabilities.add(GitCapabilityValue.value(GitCapability.SYMREF, "HEAD:" + symbolic.target().value()));
        }
        List<RefId> names = new ArrayList<>(snapshot.refs().keySet());
        names.sort(Comparator.comparing(RefId::value));
        for (RefId name : names) {
            refs.add(GitAdvertisedRef.direct(snapshot.refs().get(name).toHex(), name.value()));
        }
        if (refs.isEmpty()) {
            refs.add(GitAdvertisedRef.direct("0".repeat(40), "capabilities^{}"));
        }
        return new GitV1Advertisement(capabilities, refs);
    }
    private static GitCapabilities uploadPackCapabilities(
            GitWireConfiguration configuration) {
        GitWireConfiguration.LegacyUploadPack uploadPack =
                configuration.uploadPack();
        GitCapabilities capabilities = new GitCapabilities();
        if (uploadPack.multiAckDetailed()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.MULTI_ACK_DETAILED));
            capabilities.add(GitCapabilityValue.value(GitCapability.MULTI_ACK));
        }
        if (uploadPack.thinPack()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.THIN_PACK));
        }
        if (uploadPack.sideBand64k()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.SIDE_BAND_64K));
        }
        if (uploadPack.ofsDelta()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.OFS_DELTA));
        }
        capabilities.add(GitCapabilityValue.value(GitCapability.SHALLOW));
        capabilities.add(GitCapabilityValue.value(GitCapability.DEEPEN_SINCE));
        capabilities.add(GitCapabilityValue.value(GitCapability.DEEPEN_NOT));
        capabilities.add(GitCapabilityValue.value(GitCapability.DEEPEN_RELATIVE));
        capabilities.add(GitCapabilityValue.value(GitCapability.NO_PROGRESS));
        capabilities.add(GitCapabilityValue.value(GitCapability.INCLUDE_TAG));
        if (uploadPack.agent()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.AGENT, "orion-native"));
        }
        return capabilities;
    }

    private static GitCapabilities receivePackCapabilities(
            GitWireConfiguration configuration) {
        GitWireConfiguration.LegacyReceivePack receivePack =
                configuration.receivePack();
        GitCapabilities capabilities = new GitCapabilities();
        if (receivePack.reportStatus()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.REPORT_STATUS));
            capabilities.add(GitCapabilityValue.value(GitCapability.REPORT_STATUS_V2));
        }
        capabilities.add(GitCapabilityValue.value(GitCapability.DELETE_REFS));
        if (receivePack.sideBand64k()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.SIDE_BAND_64K));
        }
        capabilities.add(GitCapabilityValue.value(GitCapability.QUIET));
        if (receivePack.atomic()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.ATOMIC));
        }
        if (receivePack.ofsDelta()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.OFS_DELTA));
        }
        if (receivePack.objectFormat()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.OBJECT_FORMAT, "sha1"));
        }
        if (receivePack.agent()) {
            capabilities.add(GitCapabilityValue.value(GitCapability.AGENT, "orion-native"));
        }
        return capabilities;
    }

}
