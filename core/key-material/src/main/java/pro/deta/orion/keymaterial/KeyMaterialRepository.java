package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.BlobStorage.BlobStorageOperations;
import pro.deta.orion.storage.BlobStorageConflictException;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcResourceModified;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.util.VcResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PKCS12Attribute;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.security.Key;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Owns decoded material, revisions, persistence, and source-change handling without retaining serialized bytes.
 * Source notifications during a commit are deferred until its state is published. Persistence and event
 * publication do not hold the state monitor, so readers can use the last committed state during a write.
 * Failed writes invalidate this owner.
 * The addition attribute stores logical aliases, per-alias entry versions and deletion markers.
 * The content source alone supplies the whole-store revision; it is not stored in individual entries.
 */
public final class KeyMaterialRepository extends VcResource<KeyMaterialOperation> implements AutoCloseable {
    private static final String LOCAL_KEY_ID_ATTRIBUTE_OID = "1.2.840.113549.1.9.21";
    private static final String ADDITION_ATTRIBUTE_OID = "2.25.260349814076539099691640886169327826429";
    private boolean writing;
    private final BlobStorage source;
    private final KeyMaterialCodec codec;
    private final OrionEventManager eventManager;
    private KeyStore keyStore;
    private VcRevision sourceRevision;
    private boolean sourceChangePending;
    private boolean durableSnapshot;

    public KeyMaterialRepository(BlobStorage source, KeyMaterialCodec codec,
            OrionEventManager events) throws IOException, GeneralSecurityException {
        super(events);
        this.source = Objects.requireNonNull(source, "source");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (events == null) throw new IllegalArgumentException("Orion event manager must not be null");
        this.eventManager = events;
        try {
            try (BlobStorageOperations initial = source.open(new VcRevision.Latest())) {
                sourceRevision = initial.revision();
                byte[] bytes = readAvailableBytes(initial);
                try {
                    keyStore = bytes == null ? codec.create() : codec.decode(bytes);
                    durableSnapshot = bytes != null;
                } finally {
                    if (bytes != null) Arrays.fill(bytes, (byte) 0);
                }
            }
            for (String physical : physicalAliases()) entryVersion(physical);
            this.events.subscribe(VcResourceModified.class, source, this::accept);
            try {
                reload(new VcRevision.Latest());
            } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                System.getLogger(KeyMaterialRepository.class.getName()).log(System.Logger.Level.WARNING,
                        "Cannot reload key material; retaining the last valid state", failure);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            this.events.unsubscribe(VcResourceModified.class, source);
            codec.close();
            try {
                closeSource();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void accept(VcResourceModified event) {
        VcRevision announced = event.revision();
        synchronized (this) {
            if (writing) {
                sourceChangePending = true;
                return;
            }
        }
        try {
            if (announced instanceof VcRevision.Stored stored) reload(stored.value());
            else reload(new VcRevision.Latest());
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            System.getLogger(KeyMaterialRepository.class.getName()).log(System.Logger.Level.WARNING,
                    "Cannot reload key material; retaining the last valid state", failure);
        }
    }

    public record Entry(String alias, KeyStore.Entry entry, long version, boolean deleted) {}

    public synchronized Optional<Entry> find(String alias) throws GeneralSecurityException {
        requireOpen();
        return find(keyStore, alias);
    }

    Optional<Entry> find(KeyStore snapshot, String alias) throws GeneralSecurityException {
        requireAlias(alias);
        EntryVersion latest = findVersion(snapshot, alias);
        if (latest == null) return Optional.empty();
        return Optional.of(new Entry(latest.alias(), readEntry(snapshot, latest.physicalAlias()),
                latest.version(), latest.deleted()));
    }

    public synchronized Optional<Entry> find(KeyMaterialId id) throws GeneralSecurityException {
        requireOpen();
        return find(keyStore, id);
    }

    Optional<Entry> find(KeyStore snapshot, KeyMaterialId id) throws GeneralSecurityException {
        Objects.requireNonNull(id, "id");
        EntryVersion latest = findVersion(snapshot, id.alias());
        if (latest == null || latest.deleted()) return Optional.empty();
        for (String physical : physicalAliases(snapshot)) {
            EntryVersion version = entryVersion(snapshot, physical);
            if (version.alias().equalsIgnoreCase(id.alias()) && version.version() == id.version()) {
                return Optional.of(new Entry(version.alias(), readEntry(snapshot, physical),
                        version.version(), version.deleted()));
            }
        }
        return Optional.empty();
    }

    public synchronized List<Entry> entries() throws GeneralSecurityException {
        requireOpen();
        Set<String> aliases = new TreeSet<>();
        for (String physical : physicalAliases()) aliases.add(entryVersion(physical).alias());
        List<Entry> result = new ArrayList<>();
        for (String alias : aliases) result.add(find(alias).orElseThrow());
        return List.copyOf(result);
    }

    void addIfAbsent(String alias, KeyStore.Entry entry) throws IOException, GeneralSecurityException {
        try (KeyMaterialOperation operation = open(new VcRevision.Latest())) {
            if (operation.contains(alias)) return;
            operation.add(alias, entry, false);
            operation.commit(VcChangeDescription.EMPTY);
        } catch (UncheckedIOException failure) {
            if (failure.getCause() instanceof BlobStorageConflictException && find(alias).isPresent()) return;
            throw failure.getCause();
        }
    }

    synchronized boolean hasDurableSnapshot() {
        requireOpen();
        return durableSnapshot;
    }

    void append(KeyStore target, Entry addition) throws GeneralSecurityException {
        String alias = addition.alias().toLowerCase(Locale.ROOT);
        String physical = target.containsAlias(alias)
                ? "__orion_kms_" + HexFormat.of().formatHex(alias.getBytes(StandardCharsets.UTF_8))
                        + "_v" + addition.version()
                : alias;
        if (target.containsAlias(physical)) {
            throw new GeneralSecurityException("Key material version already exists: " + alias);
        }
        Set<KeyStore.Entry.Attribute> attributes = new HashSet<>();
        for (KeyStore.Entry.Attribute existing : addition.entry().getAttributes()) {
            if (!ADDITION_ATTRIBUTE_OID.equals(existing.getName())) attributes.add(existing);
        }
        String metadata = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(alias.getBytes(StandardCharsets.UTF_8))
                + ";" + addition.version() + ";" + addition.deleted();
        attributes.add(new PKCS12Attribute(ADDITION_ATTRIBUTE_OID, metadata));
        KeyStore.Entry entry = switch (addition.entry()) {
            case KeyStore.PrivateKeyEntry key -> new KeyStore.PrivateKeyEntry(
                    key.getPrivateKey(), key.getCertificateChain(), attributes);
            case KeyStore.SecretKeyEntry secret -> new KeyStore.SecretKeyEntry(secret.getSecretKey(),
                    attributes);
            case KeyStore.TrustedCertificateEntry trusted -> new KeyStore.TrustedCertificateEntry(
                    trusted.getTrustedCertificate(), attributes);
            default -> throw new GeneralSecurityException("Unknown key material entry type");
        };
        char[] password = codec.password();
        try {
            KeyStore.ProtectionParameter protection = entry instanceof KeyStore.TrustedCertificateEntry
                    ? null : new KeyStore.PasswordProtection(password);
            target.setEntry(physical, entry, protection);
        } finally {
            clear(password);
        }
    }

    private List<String> physicalAliases() throws GeneralSecurityException {
        return physicalAliases(keyStore);
    }

    private static List<String> physicalAliases(KeyStore source) throws GeneralSecurityException {
        List<String> aliases = new ArrayList<>();
        source.aliases().asIterator().forEachRemaining(aliases::add);
        return aliases;
    }

    private EntryVersion findVersion(String alias) throws GeneralSecurityException {
        return findVersion(keyStore, alias);
    }

    private EntryVersion findVersion(KeyStore snapshot, String alias) throws GeneralSecurityException {
        EntryVersion latest = null;
        for (String physical : physicalAliases(snapshot)) {
            EntryVersion entry = entryVersion(snapshot, physical);
            if (entry.alias().equalsIgnoreCase(alias)
                    && (latest == null || entry.version() > latest.version())) latest = entry;
        }
        return latest;
    }

    private EntryVersion entryVersion(String physical) throws GeneralSecurityException {
        return entryVersion(keyStore, physical);
    }

    private static EntryVersion entryVersion(KeyStore source, String physical) throws GeneralSecurityException {
        String metadata = attribute(source.getAttributes(physical), ADDITION_ATTRIBUTE_OID);
        if (metadata == null) return new EntryVersion(physical, physical, 1, false);
        try {
            String[] fields = metadata.split(";", -1);
            if (fields.length != 3 || (!fields[2].equals("true") && !fields[2].equals("false"))) {
                throw new IllegalArgumentException();
            }
            String alias = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
            long entryVersion = Long.parseLong(fields[1]);
            if (alias.isBlank() || entryVersion < 1) throw new IllegalArgumentException();
            return new EntryVersion(physical, alias, entryVersion, Boolean.parseBoolean(fields[2]));
        } catch (IllegalArgumentException failure) {
            throw new GeneralSecurityException("Invalid key material addition: " + physical, failure);
        }
    }

    private static String attribute(Set<KeyStore.Entry.Attribute> attributes, String oid) {
        for (KeyStore.Entry.Attribute attribute : attributes) {
            if (oid.equals(attribute.getName())) return attribute.getValue();
        }
        return null;
    }

    private record EntryVersion(String physicalAlias, String alias, long version, boolean deleted) {}

    public synchronized String revision() {
        requireOpen();
        return sourceRevision instanceof VcRevision.Stored stored ? stored.value() : null;
    }

    @Override
    protected KeyMaterialOperation createOperation(VcRevision.ReadTarget target) {
        VcRevision.ReadTarget revision;
        synchronized (this) {
            requireOpen();
            revision = target instanceof VcRevision.Latest ? switch (sourceRevision) {
                case VcRevision.Stored stored -> stored;
                case VcRevision.Absent absent -> absent;
            } : target;
        }
        BlobStorageOperations operation = source.open(revision);
        try {
            byte[] bytes = readAvailableBytes(operation);
            try {
                KeyStore snapshot = bytes == null ? codec.create() : codec.decode(bytes);
                return new KeyMaterialOperation(this, operation, snapshot);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("Cannot decode key material", failure);
            } finally {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
            }
        } catch (RuntimeException | Error failure) {
            try {
                operation.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    VcRevision.Stored commit(BlobStorageOperations operation, KeyStore snapshot,
            Set<String> changedAliases, VcChangeDescription description) throws IOException {
        try {
            Set<String> resources = new HashSet<>();
            VcRevision.Stored committed = applyState(operation, snapshot, changedAliases, description, resources);
            if (!resources.isEmpty()) publishChanged(resources);
            return committed;
        } finally {
            processPendingSourceChange();
        }
    }

    private void processPendingSourceChange() {
        synchronized (this) {
            if (keyStore == null) {
                sourceChangePending = false;
                return;
            }
            if (writing || !sourceChangePending) return;
            sourceChangePending = false;
        }
        try {
            reload(new VcRevision.Latest());
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            System.getLogger(KeyMaterialRepository.class.getName()).log(System.Logger.Level.WARNING,
                    "Cannot refresh material source after save; retaining the committed state", failure);
        }
    }

    private VcRevision.Stored applyState(BlobStorageOperations operation, KeyStore snapshot,
            Set<String> changedAliases, VcChangeDescription description, Set<String> resources) throws IOException {
        VcRevision expectedRevision = operation.revision();
        synchronized (this) {
            writing = true;
        }
        try {
            byte[] serialized;
            KeyStore publication;
            synchronized (this) {
                requireOpen();
                if (!Objects.equals(sourceRevision, expectedRevision)) {
                    throw new BlobStorageConflictException("Key material changed before apply");
                }
                if (changedAliases.isEmpty() && operation.revision() instanceof VcRevision.Stored stored) return stored;
                try {
                    serialized = codec.encode(snapshot);
                    try {
                        publication = codec.decode(serialized);
                    } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                        Arrays.fill(serialized, (byte) 0);
                        throw failure;
                    }
                } catch (GeneralSecurityException failure) {
                    close();
                    throw new IOException("Cannot prepare key material", failure);
                } catch (IOException | RuntimeException failure) {
                    close();
                    throw failure;
                }
            }
            try {
                VcRevision.Stored saved;
                try {
                    operation.write(serialized);
                    saved = operation.commit(description);
                } catch (VcModificationException conflict) {
                    throw new BlobStorageConflictException("Key material changed before apply", conflict);
                } catch (UncheckedIOException failure) {
                    throw failure.getCause();
                } finally {
                    Arrays.fill(serialized, (byte) 0);
                }
                synchronized (this) {
                    requireOpen();
                    if (Objects.equals(sourceRevision, expectedRevision)) {
                        keyStore = publication;
                        resources.addAll(changedAliases);
                        sourceRevision = saved;
                        durableSnapshot = true;
                    } else {
                        for (String physical : physicalAliases(publication)) {
                            if (!keyStore.containsAlias(physical)
                                    || !sameEntry(readEntry(keyStore, physical), readEntry(publication,
                                            physical))) {
                                throw new GeneralSecurityException("Reloaded material does not contain saved history");
                            }
                        }
                    }
                }
                return saved;
            } catch (GeneralSecurityException failure) {
                close();
                throw new IOException("Cannot save key material", failure);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        } finally {
            synchronized (this) {
                writing = false;
            }
        }
    }

    public void reload(String requestedRevision) throws IOException, GeneralSecurityException {
        synchronized (this) {
            requireOpen();
            Objects.requireNonNull(requestedRevision, "revision");
            if (Objects.equals(sourceRevision, new VcRevision.Stored(requestedRevision))) return;
        }
        reload(new VcRevision.Stored(requestedRevision));
    }

    private void reload(VcRevision.ReadTarget revision) throws IOException, GeneralSecurityException {
        Set<String> resources;
        try (BlobStorageOperations operation = source.open(revision)) {
            resources = reloadState(operation);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        if (!resources.isEmpty()) publishChanged(resources);
    }

    private synchronized Set<String> reloadState(BlobStorageOperations content)
            throws IOException, GeneralSecurityException {
        KeyStore incoming;
        byte[] bytes = readAvailableBytes(content);
        try {
            requireOpen();
            if (Objects.equals(sourceRevision, content.revision())) return Set.of();
            incoming = bytes == null ? codec.create() : codec.decode(bytes);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
        for (String physical : physicalAliases()) {
            if (!incoming.containsAlias(physical)
                    || !sameEntry(readEntry(keyStore, physical), readEntry(incoming, physical))) {
                throw new GeneralSecurityException("Key material history was replaced or removed: " + physical);
            }
        }
        Set<String> resources = new HashSet<>();
        Set<String> entryVersions = new HashSet<>();
        for (String physical : physicalAliases(incoming)) {
            EntryVersion entry = entryVersion(incoming, physical);
            if (!entryVersions.add(entry.alias() + ":" + entry.version())) {
                throw new GeneralSecurityException("Duplicate key material version: " + entry.alias());
            }
            if (keyStore.containsAlias(physical)) continue;
            EntryVersion previous = findVersion(entry.alias());
            if (previous != null && entry.version() <= previous.version()) {
                throw new GeneralSecurityException("Key material revision does not extend active history");
            }
            readEntry(incoming, physical);
            resources.add(entry.alias());
        }
        keyStore = incoming;
        sourceRevision = content.revision();
        durableSnapshot = bytes != null;
        return Set.copyOf(resources);
    }

    private byte[] readAvailableBytes(BlobStorageOperations operation) {
        try {
            return operation.readContent();
        } catch (UncheckedIOException failure) {
            if (failure.getCause() instanceof FileNotFoundException) return null;
            throw failure;
        }
    }

    private void closeSource() {
        if (source instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot close key material source", failure);
            }
        }
    }

    private KeyStore.Entry readEntry(KeyStore source, String physical) throws GeneralSecurityException {
        char[] password = codec.password();
        try {
            boolean trusted = source.entryInstanceOf(physical, KeyStore.TrustedCertificateEntry.class);
            KeyStore.ProtectionParameter protection = trusted ? null : new KeyStore.PasswordProtection(password);
            return source.getEntry(physical, protection);
        } finally {
            clear(password);
        }
    }

    private static boolean sameEntry(KeyStore.Entry first, KeyStore.Entry second) {
        if (!stableAttributes(first).equals(stableAttributes(second))) return false;
        return switch (first) {
            case KeyStore.PrivateKeyEntry key -> second instanceof KeyStore.PrivateKeyEntry other
                    && sameKey(key.getPrivateKey(), other.getPrivateKey())
                    && Arrays.equals(key.getCertificateChain(), other.getCertificateChain());
            case KeyStore.SecretKeyEntry key -> second instanceof KeyStore.SecretKeyEntry other
                    && sameKey(key.getSecretKey(), other.getSecretKey());
            case KeyStore.TrustedCertificateEntry trusted -> second instanceof KeyStore.TrustedCertificateEntry other
                    && trusted.getTrustedCertificate().equals(other.getTrustedCertificate());
            default -> false;
        };
    }

    private static Set<KeyStore.Entry.Attribute> stableAttributes(KeyStore.Entry entry) {
        Set<KeyStore.Entry.Attribute> attributes = new HashSet<>();
        for (KeyStore.Entry.Attribute attribute : entry.getAttributes()) {
            if (!LOCAL_KEY_ID_ATTRIBUTE_OID.equals(attribute.getName())) attributes.add(attribute);
        }
        return attributes;
    }

    private static boolean sameKey(Key first, Key second) {
        byte[] firstEncoded = first.getEncoded();
        byte[] secondEncoded = second.getEncoded();
        try {
            return firstEncoded != null && secondEncoded != null
                    && MessageDigest.isEqual(firstEncoded, secondEncoded);
        } finally {
            if (firstEncoded != null) Arrays.fill(firstEncoded, (byte) 0);
            if (secondEncoded != null) Arrays.fill(secondEncoded, (byte) 0);
        }
    }

    private void publishChanged(Set<String> resources) {
        try {
            if (eventManager.isRunning()) events.publish(new KeyMaterialChanged(Set.copyOf(resources)));
        } catch (RuntimeException failure) {
            System.getLogger(KeyMaterialRepository.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not publish key material change event", failure);
        }
    }

    private static void requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
    }

    private static void clear(char[] value) {
        Arrays.fill(value, '\0');
    }

    synchronized void requireOpen() {
        if (keyStore == null) {
            throw new IllegalStateException("Key material store is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (keyStore == null) return;
        keyStore = null;
        try {
            this.events.unsubscribe(VcResourceModified.class, source);
        } finally {
            try {
                codec.close();
            } finally {
                closeSource();
            }
        }
    }
}
