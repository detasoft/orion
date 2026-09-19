package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns one upload's input position, checksum state, retained pack bytes, and storage-provided PackIndex.
 * index returns the same borrowed index, initially empty. Storage controls its placement and may write
 * records and waiting dependencies incrementally without keeping the entire index in memory.
 * storage returns the owning repository's borrowed GitStorageApi for reading published external bases.
 * Distinct attempts own isolated resources even if their eventual PackIds match.
 * One PackByteStore supplies both the parser's append sink and positional reads of retained raw bytes.
 * Parser and content handles borrow it; upload owns its lifetime. FileChannel and paths stay behind this
 * interface, and reading accepted writes does not require a durability flush.
 *
 * <p>hasNext validates the pack header and tracks its declared entry count. next uses the static
 * PackObjectParser.parseEntry to consume an entry, then calls index.addEntry with its physical metadata.
 * Its processor selects HashedGitObjectRead for full objects and returns their IDs in Optional; upload
 * completes their records through index.addObject immediately. For deltas it returns Optional.empty;
 * parsing still consumes and validates their payload, retaining original bytes for subsequent resolver reads.
 * Delta index records remain unresolved until the resolver completes them. No inflated payload cache is kept.
 * next returns metadata and Optional<ObjectId>, with no read handle to close. Retention or index failures
 * stop the attempt and prevent successful completion. A failed attempt cannot resume reading transport input.
 * next never returns null and throws NoSuchElementException after successful exhaustion. Repeated hasNext
 * calls do not consume another entry. After the declared entries, hasNext verifies the checksum before
 * returning false. Truncation and malformed input are IOException, not normal exhaustion.
 * packId returns the verified checksum after hasNext has returned false, including for an empty pack;
 * before successful completion it fails with IllegalStateException. It performs no I/O.
 * Original bytes, including pack header, entries, and checksum, are retained exactly once. The checksum is
 * computed over header and entries, excluding the trailing checksum itself. Subsequent protocol bytes stay
 * available through the same caller-owned source and never enter the raw sink.
 * During the first pass, each original byte block is both retained and fed once to a streaming digest
 * accumulator, such as MessageDigest.update. The trailing checksum is retained but excluded from update;
 * compare it with the final digest to obtain PackId. Neither hashing nor entry scanning loads the whole pack
 * into memory. Inflated payload is inspected with bounded buffers to locate and validate zlib boundaries.
 * For full objects, a separate digest includes the canonical object header and inflated bytes to compute
 * ObjectId before discarding them. Delta instructions are not hashed as objects. Index records contain
 * offsets, types, lengths, base references, and any ObjectId already computed for a full object.
 *
 * <p>readObject(entryOffset, reader) invokes the processor with physical type, inflated size, and a bounded
 * borrowed zlib source from retained bytes, then returns its nonnull result. The absolute offset identifies
 * an indexed entry already returned by next. Negative or unknown offsets fail with IllegalArgumentException.
 * RawGitObjectRead forwards compressed bytes; ContentGitObjectRead inflates them before calling its consumer.
 * Full objects hashed on the first pass can be reread here as bases. Deltas expose instructions even if the
 * index knows their final ObjectId. Neither this method nor the static parser applies deltas.
 * Reads never advance iteration or reread transport input. Upload owns and closes the invocation source;
 * readers cannot retain it. Any returned resource must have independent ownership and be closed before rollback.
 * Failed stored reads or processors stop the attempt, so failed validation or resolution cannot publish a pack.
 * Access after rollback fails with ClosedChannelException. The resolver owns reconstruction and base reads;
 * the ingestor reads no payloads. No additional inflated-content storage is required.
 *
 * <p>commit(packId) requires completed iteration and a matching verified checksum, even for an empty pack.
 * It checks index.hasUnresolved itself and refuses publication with IOException when any unfinished record
 * or chain remains. Index lookup errors also prevent publication; no separate ingestor check is required.
 * Before publication, the index identifies external bases that must be appended to complete the pack.
 * Storage completes the pack, finishes pending byte and index writes, and durably publishes it
 * together with its populated index under the final PackId lock. No complete index collection is transferred
 * from caller memory.
 * Temporary waiting structures may then be removed; completed index records remain published.
 * Mutations after commit are rejected. An I/O failure during publication can have an uncertain outcome;
 * retries inspect durable publication metadata. Validation rejection publishes nothing.
 *
 * <p>rollback releases byte storage and index resources belonging to this attempt and discards only unpublished
 * staging. It is idempotent and safe after commit: published data and other attempts remain intact.
 * Call it in finally without masking the original failure. Neither outcome closes caller-owned source input.
 * Recovery ignores incomplete staging. Methods are sequential.
 *
 * <p>The constructor stores the repository, borrowed input, and owned byte store and index without doing I/O.
 * checksum is the streaming SHA-1 accumulator for the current GitId representation. offset tracks retained
 * original bytes; remainingEntries is -1 until the pack header has been parsed. verifiedPackId stays null
 * until the entry count and trailer have been validated. No object payload cache is kept here.
 * Pack version 2, header/entry iteration, checksum validation, index registration, and positional content
 * reads are implemented. Backend owns finalization, publication, and rollback of this attempt.
 * After commit, packId returns the final checksum, which may differ when thin-pack bases were appended.
 * Parsing and content access end at commit; rollback remains safe and never removes published data.
 */
public final class PackUpload implements AutoCloseable {
    private final GitStorageApi storage;
    private final BufferedByteInput source;
    private final PackByteStore byteStore;
    private final PackIndex index;
    private final Backend backend;
    private PackId committedPackId;
    private final MessageDigest checksum;
    private final PackByteStore entryStore = new ChecksummedStore();

    private long offset;
    private long remainingEntries = -1;
    private PackId verifiedPackId;
    private Throwable failure;

    public PackUpload(GitStorageApi storage, BufferedByteInput source, Backend backend) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.source = Objects.requireNonNull(source, "source");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.byteStore = Objects.requireNonNull(backend.bytes(), "byteStore");
        this.index = Objects.requireNonNull(backend.index(), "index");
        try {
            this.checksum = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required for Git pack checksums", e);
        }
    }

    public GitStorageApi storage() {
        return storage;
    }

    public PackIndex index() {
        return index;
    }

    public boolean hasNext() throws IOException {
        requireUsable();
        if (verifiedPackId != null) {
            return false;
        }
        try {
            if (remainingEntries == -1) {
                byte[] header = source.readBytes(12);
                ByteBuffer fields = ByteBuffer.wrap(header);
                if (fields.getInt() != 0x5041434b) {
                    throw new IOException("Invalid pack magic bytes");
                }
                int version = fields.getInt();
                if (version != 2) {
                    throw new IOException("Unsupported pack version: " + version);
                }
                remainingEntries = Integer.toUnsignedLong(fields.getInt());
                retain(header, true);
            }
            if (remainingEntries != 0) {
                return true;
            }
            byte[] trailer = source.readBytes(20);
            retain(trailer, false);
            if (!MessageDigest.isEqual(checksum.digest(), trailer)) {
                throw new IOException("Pack checksum mismatch");
            }
            verifiedPackId = new PackId(trailer);
            return false;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        }
    }

    public PackObjectParser.Result<Optional<ObjectId>> next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException("Pack is exhausted");
        }
        try {
            var result = PackObjectParser.parseEntry(source, offset, entryStore, (type, size, baseId, raw) ->
                    switch (type) {
                        case OFS_DELTA, REF_DELTA -> Optional.<ObjectId>empty();
                        case COMMIT, TREE, BLOB, TAG ->
                                Optional.of(new HashedGitObjectRead().read(type, size, baseId, raw));
                    });
            index.addEntry(result.entry());
            if (result.value().isPresent()) {
                index.addObject(result.entry(), result.value().orElseThrow(),
                        result.entry().type(), result.entry().inflatedSize());
            }
            remainingEntries--;
            return result;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        }
    }

    public PackId packId() {
        if (verifiedPackId == null || failure != null) {
            throw new IllegalStateException("Pack checksum has not been verified");
        }
        return committedPackId == null ? verifiedPackId : committedPackId;
    }

    public <R> R readObject(long entryOffset, GitObjectRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        if (entryOffset < 0) {
            throw new IllegalArgumentException("Entry offset must be non-negative");
        }
        requireUsable();
        PackObjectParser.Entry entry;
        try {
            entry = index.find(entryOffset).orElse(null);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        }
        if (entry == null) {
            throw new IllegalArgumentException("Unknown pack entry offset: " + entryOffset);
        }
        try {
            return PackObjectParser.readStored(entry, byteStore, offset, reader);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        }
    }

    public void commit(PackId packId) throws IOException {
        Objects.requireNonNull(packId, "packId");
        requireUsable();
        if (!packId.equals(packId())) {
            throw new IOException("Pack ID does not match the verified checksum");
        }
        if (index.hasUnresolved()) {
            throw new IOException("Pack contains unresolved objects");
        }
        try {
            committedPackId = Objects.requireNonNull(backend.commit(packId), "committed pack ID");
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        if (committedPackId != null) {
            return;
        }
        backend.rollback();
    }

    private void requireUsable() throws IOException {
        if (!byteStore.isOpen()) {
            throw new ClosedChannelException();
        }
        if (committedPackId != null) {
            throw new IllegalStateException("Pack upload is already committed");
        }
        if (failure != null) {
            throw new IOException("Pack upload failed and cannot continue", failure);
        }
    }

    private void retain(byte[] bytes, boolean hash) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            retain(buffer, hash);
        }
    }

    private int retain(ByteBuffer bytes, boolean hash) throws IOException {
        if (!bytes.hasRemaining()) {
            return 0;
        }
        if (bytes.remaining() > Long.MAX_VALUE - offset) {
            throw new IOException("Pack offset overflows a signed long");
        }
        int start = bytes.position();
        int count = byteStore.write(bytes);
        if (count <= 0) {
            throw new IOException("Pack byte store made no write progress");
        }
        if (hash) {
            checksum.update(bytes.duplicate().position(start).limit(start + count));
        }
        offset += count;
        return count;
    }

    private final class ChecksummedStore implements PackByteStore {
        @Override
        public int write(ByteBuffer bytes) throws IOException {
            return retain(bytes, true);
        }

        @Override
        public int read(long position, ByteBuffer destination) throws IOException {
            return byteStore.read(position, destination);
        }

        @Override
        public void force() throws IOException {
            byteStore.force();
        }

        @Override
        public boolean isOpen() {
            return byteStore.isOpen();
        }

        @Override
        public void close() throws IOException {
            byteStore.close();
        }
    }

    /**
     * Owns one attempt's byte store and index across parsing, publication, and cleanup.
     * commit returns the final PackId and releases writable handles; rollback is idempotent,
     * closes both handles, and removes only this attempt's unpublished files. Neither closes input.
     */
    public interface Backend {
        PackByteStore bytes();

        PackIndex index();

        PackId commit(PackId receivedId) throws IOException;

        void rollback() throws IOException;
    }
}
