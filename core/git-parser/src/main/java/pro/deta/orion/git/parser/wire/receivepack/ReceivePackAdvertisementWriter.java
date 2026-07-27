package pro.deta.orion.git.parser.wire.receivepack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitCapabilityWriter;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReceivePackAdvertisementWriter {
    private static final String NULL_ID = ReceivePackCommand.NULL_ID;
    private static final String CAPABILITIES_REF = "capabilities^{}";

    private final GitCapabilityWriter capabilityWriter = new GitCapabilityWriter();

    public List<ByteBuf> write(
            GitPktLineWriter pktLineWriter,
            Map<String, String> refs,
            Collection<ReceivePackCapability> advertised) {
        Objects.requireNonNull(pktLineWriter, "pktLineWriter");
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(advertised, "advertised");

        List<GitCapability> caps = new ArrayList<>();
        for (ReceivePackCapability cap : advertised) {
            caps.add(cap.toCapability());
        }

        List<ByteBuf> packets = new ArrayList<>();
        try {
            if (refs.isEmpty()) {
                String capLine = capabilityWriter.writeAdvertisementLine(NULL_ID + " " + CAPABILITIES_REF, caps);
                packets.add(pktLineWriter.writeTextLine(capLine));
            } else {
                boolean first = true;
                for (Map.Entry<String, String> entry : refs.entrySet()) {
                    String refLine = entry.getValue() + " " + entry.getKey();
                    if (first) {
                        refLine = capabilityWriter.writeAdvertisementLine(refLine, caps);
                        first = false;
                    }
                    packets.add(pktLineWriter.writeTextLine(refLine));
                }
            }
            packets.add(pktLineWriter.writeFlush());
            return List.copyOf(packets);
        } catch (RuntimeException error) {
            for (ByteBuf packet : packets) {
                packet.release();
            }
            throw error;
        }
    }
}
