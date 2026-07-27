package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NativeLsRefsService {
    private final GitPktLineWriter pktLineWriter;

    public NativeLsRefsService(ByteBufAllocator allocator) {
        pktLineWriter = new GitPktLineWriter(Objects.requireNonNull(allocator, "allocator"));
    }

    public List<ByteBuf> advertise(
            LooseRefStore refs,
            Optional<String> headTarget,
            boolean includeSymrefs,
            boolean includeUnborn) {
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(headTarget, "headTarget");

        Map<String, String> snapshot = refs.snapshot();
        List<ByteBuf> packets = new ArrayList<>();
        addHead(packets, snapshot, headTarget, includeSymrefs, includeUnborn);

        List<String> refNames = new ArrayList<>();
        for (String refName : snapshot.keySet()) {
            if (refName.startsWith("refs/heads/") || refName.startsWith("refs/tags/")) {
                refNames.add(refName);
            }
        }
        Collections.sort(refNames);
        for (String refName : refNames) {
            packets.add(pktLineWriter.writeTextLine(snapshot.get(refName) + " " + refName));
        }
        packets.add(pktLineWriter.writeFlush());
        return List.copyOf(packets);
    }

    private void addHead(
            List<ByteBuf> packets,
            Map<String, String> refs,
            Optional<String> headTarget,
            boolean includeSymrefs,
            boolean includeUnborn) {
        if (headTarget.isEmpty()) {
            return;
        }
        String target = headTarget.get();
        String suffix = includeSymrefs ? " symref-target:" + target : "";
        String objectId = refs.get(target);
        if (objectId != null) {
            packets.add(pktLineWriter.writeTextLine(objectId + " HEAD" + suffix));
        } else if (includeUnborn) {
            packets.add(pktLineWriter.writeTextLine("unborn HEAD" + suffix));
        }
    }
}
