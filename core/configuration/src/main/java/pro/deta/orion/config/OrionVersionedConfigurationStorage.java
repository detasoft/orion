package pro.deta.orion.config;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.VcRevision;

/**
 * Reads document results with independent storage revisions and saves against an expected revision.
 * Reading throws if the storage revision cannot be determined; Absent means confirmed absence.
 */
public interface OrionVersionedConfigurationStorage {
    OrionDocumentRevision load();

    OrionDocumentRevision load(VcRevision.Stored revision);

    VcRevision.Stored save(VcRevision expectedRevision, OrionDocument document, ChangeMetadata metadata);
}
