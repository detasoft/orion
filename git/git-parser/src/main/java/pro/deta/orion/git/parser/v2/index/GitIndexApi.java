package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;

import java.io.IOException;
import java.util.List;

/** Repository reference index, independent of pack storage; its owner controls its lifetime. */
public interface GitIndexApi extends AutoCloseable {
    RefsSnapshot snapshotRefs() throws IOException;

    void updateHead(Head head) throws IOException;

    List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic);

    void close() throws IOException;
}
