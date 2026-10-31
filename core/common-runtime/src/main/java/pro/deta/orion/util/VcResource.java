package pro.deta.orion.util;

public interface VcResource {
    VcRevision getRevision();

    /**
     * responsibilities: the changes being applied, have to commit and update revision.
     *
     * @param vcChangeDescription
     */
    void commit(VcChangeDescription vcChangeDescription);

    Modification<? extends VcResource> withModification();
}
