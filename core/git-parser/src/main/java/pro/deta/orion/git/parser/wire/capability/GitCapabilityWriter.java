package pro.deta.orion.git.parser.wire.capability;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GitCapabilityWriter {

    public String writeAdvertisementLine(String advertisementLine, List<GitCapability> capabilities) {
        Objects.requireNonNull(advertisementLine, "advertisementLine");
        Objects.requireNonNull(capabilities, "capabilities");
        if (advertisementLine.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Advertisement line must not already contain capability separator");
        }
        if (capabilities.isEmpty()) {
            return advertisementLine;
        }
        return advertisementLine + '\0' + writeCapabilityList(capabilities);
    }

    public ByteBuf writeAdvertisementPacket(
            GitPktLineWriter pktLineWriter,
            String advertisementLine,
            List<GitCapability> capabilities) {
        Objects.requireNonNull(pktLineWriter, "pktLineWriter");
        return pktLineWriter.writeTextLine(writeAdvertisementLine(advertisementLine, capabilities));
    }

    public String writeCapabilityList(List<GitCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        StringBuilder result = new StringBuilder();
        for (GitCapability capability : capabilities) {
            Objects.requireNonNull(capability, "capability");
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(capability.rawToken());
        }
        return result.toString();
    }

    public List<String> writeProtocolV2Lines(List<GitCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        List<String> lines = new ArrayList<>();
        for (GitCapability capability : capabilities) {
            Objects.requireNonNull(capability, "capability");
            lines.add(capability.rawToken() + '\n');
        }
        return List.copyOf(lines);
    }

    public List<ByteBuf> writeProtocolV2Packets(GitPktLineWriter pktLineWriter, List<GitCapability> capabilities) {
        Objects.requireNonNull(pktLineWriter, "pktLineWriter");
        List<ByteBuf> packets = new ArrayList<>();
        for (String line : writeProtocolV2Lines(capabilities)) {
            packets.add(pktLineWriter.writeText(line));
        }
        return List.copyOf(packets);
    }
}
