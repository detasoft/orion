package pro.deta.orion.util;

import java.util.List;
import java.util.function.Consumer;

public interface VcResource {
    VcRevision getRevision();

    void commit(VcChangeDescription vcChangeDescription, List<Consumer<? extends VcResource>> changes);

    Modification<? extends VcResource> modify();

    Modification<? extends VcResource> modify();
}
