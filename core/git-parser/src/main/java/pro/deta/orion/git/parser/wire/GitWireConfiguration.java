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
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true));
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
            boolean shallow,
            boolean waitForDone,
            boolean serverOption,
            boolean filter,
            boolean refInWant,
            boolean sidebandAll,
            boolean packfileUris) {

        public ProtocolV2(
                boolean lsRefs,
                boolean lsRefsUnborn,
                boolean fetch,
                boolean waitForDone,
                boolean serverOption) {
            this(
                    lsRefs,
                    lsRefsUnborn,
                    fetch,
                    false,
                    waitForDone,
                    serverOption,
                    false,
                    false,
                    false,
                    false);
        }

        public ProtocolV2(
                boolean lsRefs,
                boolean lsRefsUnborn,
                boolean fetch,
                boolean serverOption) {
            this(
                    lsRefs,
                    lsRefsUnborn,
                    fetch,
                    false,
                    false,
                    serverOption,
                    false,
                    false,
                    false,
                    false);
        }

        public ProtocolV2(
                boolean lsRefs,
                boolean lsRefsUnborn,
                boolean fetch,
                boolean shallow,
                boolean waitForDone,
                boolean serverOption) {
            this(
                    lsRefs,
                    lsRefsUnborn,
                    fetch,
                    shallow,
                    waitForDone,
                    serverOption,
                    false,
                    false,
                    false,
                    false);
        }

        public ProtocolV2(
                boolean lsRefs,
                boolean lsRefsUnborn,
                boolean fetch,
                boolean shallow,
                boolean waitForDone,
                boolean serverOption,
                boolean filter) {
            this(
                    lsRefs,
                    lsRefsUnborn,
                    fetch,
                    shallow,
                    waitForDone,
                    serverOption,
                    filter,
                    false,
                    false,
                    false);
        }

        public ProtocolV2(
                boolean lsRefs,
                boolean lsRefsUnborn,
                boolean fetch,
                boolean shallow,
                boolean waitForDone,
                boolean serverOption,
                boolean filter,
                boolean refInWant) {
            this(
                    lsRefs,
                    lsRefsUnborn,
                    fetch,
                    shallow,
                    waitForDone,
                    serverOption,
                    filter,
                    refInWant,
                    false,
                    false);
        }

        public ProtocolV2(
                boolean lsRefs,
                boolean lsRefsUnborn,
                boolean fetch,
                boolean shallow,
                boolean waitForDone,
                boolean serverOption,
                boolean filter,
                boolean refInWant,
                boolean sidebandAll) {
            this(
                    lsRefs,
                    lsRefsUnborn,
                    fetch,
                    shallow,
                    waitForDone,
                    serverOption,
                    filter,
                    refInWant,
                    sidebandAll,
                    false);
        }

        public ProtocolV2 {
            if (lsRefsUnborn && !lsRefs) {
                throw new IllegalArgumentException(
                        "lsRefsUnborn requires lsRefs");
            }
            if (shallow && !fetch) {
                throw new IllegalArgumentException(
                        "shallow requires fetch");
            }
            if (waitForDone && !fetch) {
                throw new IllegalArgumentException(
                        "waitForDone requires fetch");
            }
            if (filter && !fetch) {
                throw new IllegalArgumentException(
                        "filter requires fetch");
            }
            if (refInWant && !fetch) {
                throw new IllegalArgumentException(
                        "refInWant requires fetch");
            }
            if (sidebandAll && !fetch) {
                throw new IllegalArgumentException(
                        "sidebandAll requires fetch");
            }
            if (packfileUris && !fetch) {
                throw new IllegalArgumentException(
                        "packfileUris requires fetch");
            }
        }
    }
}
