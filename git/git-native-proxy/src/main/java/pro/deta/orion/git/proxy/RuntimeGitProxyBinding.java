package pro.deta.orion.git.proxy;

import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;

import java.util.List;

public interface RuntimeGitProxyBinding {
    void refresh();

    List<RefUpdateResult> publish(
            PackIngestionResult.Complete received,
            List<LooseRefStore.Update> updates,
            boolean atomic);
}
