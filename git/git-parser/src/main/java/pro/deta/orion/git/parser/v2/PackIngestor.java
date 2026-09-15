package pro.deta.orion.git.parser.v2;

/**
 * Resolves pack objects for the command that owns ingestion, including internal and external delta bases.
 * PushCommand owns its lifecycle. Reception and storage resources remain behind GitStorageApi.
 * The ingestion contract and method signatures will be defined separately.
 */
public final class PackIngestor {
}
