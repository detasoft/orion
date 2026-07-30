package pro.deta.orion.git.parser.wire;

import java.util.Objects;

public record GitWireConfiguration(
        LegacyUploadPack uploadPack,
        LegacyReceivePack receivePack,
        ProtocolV2 protocolV2) {

    public GitWireConfiguration {
        Objects.requireNonNull(uploadPack, "uploadPack");
        Objects.requireNonNull(receivePack, "receivePack");
        Objects.requireNonNull(protocolV2, "protocolV2");
    }

    public static GitWireConfiguration allSupported() {
        return new GitWireConfiguration(
                new LegacyUploadPack(
                        true, true, true, true, true, true),
                new LegacyReceivePack(
                        true, true, true, true, true),
                new ProtocolV2(
                        true, true, true, true));
    }

    public record LegacyUploadPack(
            boolean multiAckDetailed,
            boolean thinPack,
            boolean sideBand64k,
            boolean ofsDelta,
            boolean symref,
            boolean agent) {
    }

    public record LegacyReceivePack(
            boolean reportStatus,
            boolean sideBand64k,
            boolean ofsDelta,
            boolean objectFormat,
            boolean agent) {
    }

    public record ProtocolV2(
            boolean lsRefs,
            boolean lsRefsUnborn,
            boolean fetch,
            boolean serverOption) {

        public ProtocolV2 {
            if (lsRefsUnborn && !lsRefs) {
                throw new IllegalArgumentException(
                        "lsRefsUnborn requires lsRefs");
            }
        }
    }
}
