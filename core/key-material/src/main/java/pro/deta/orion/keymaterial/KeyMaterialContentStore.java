package pro.deta.orion.keymaterial;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.Subscription;

import java.io.IOException;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns decoded material, revisions, persistence, and source-change handling without retaining serialized bytes.
 * Writer coordination is separate from state access. Source notifications during a write are deferred until
 * it finishes, and material events are published outside both locks. Failed writes invalidate this owner.
 * The addition attribute OID stores logical aliases, entry versions, KMS revisions and deletion markers;
 * its persisted identifier and format must remain stable.
 */
public final class KeyMaterialContentStore implements AutoCloseable {
    private static final String LOCAL_KEY_ID_ATTRIBUTE_OID = "1.2.840.113549.1.9.21";
    private static final String ADDITION_ATTRIBUTE_OID = "2.25.260349814076539099691640886169327826429";
    private final ReentrantLock writes = new ReentrantLock();
    private final KeyMaterialContentSource source;
    private final KeyMaterialCodec codec;
    private final OrionEventManager events;
    private final Subscription changes;
    private KeyStore keyStore;
    private String sourceRevision;
    private boolean sourceChangePending;
    private long revision;

    public KeyMaterialContentStore(KeyMaterialContentSource source, KeyMaterialCodec codec,
            OrionEventManager events) throws IOException, GeneralSecurityException {
        this.source = Objects.requireNonNull(source, "source");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (events == null) throw new IllegalArgumentException("Orion event manager must not be null");
        this.events = events;
        Subscription subscribed = null;
        try {
            Optional<String> stored = source.revision();
            if (stored.isPresent()) {
                sourceRevision = stored.orElseThrow();
                byte[] bytes = source.read(sourceRevision);
                try {
                    keyStore = codec.decode(bytes);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            } else {
                keyStore = codec.create();
            }
            for (String alias : physicalAliases()) {
                revision = Math.max(revision, entryVersion(alias).kmsVersion());
            }
            subscribed = events.subscribe(KeyMaterialSourceChanged.class, event -> {
                if (event.source() == source) reloadChangedSource(event.revision());
            });
            changes = subscribed;
            Optional<String> current = source.revision();
            if (current.isPresent()) {
                reloadChangedSource(current.orElseThrow());
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (subscribed != null) subscribed.close();
            codec.close();
            try {
                source.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void reloadChangedSource(String sourceRevision) {
        synchronized (this) {
            if (writes.isLocked()) {
                sourceChangePending = true;
                return;
            }
        }
        try {
            reload(sourceRevision);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            System.getLogger(KeyMaterialContentStore.class.getName()).log(System.Logger.Level.WARNING,
                    "Cannot reload key material; retaining the last valid state", failure);
        }
    }

    public record Entry(String alias, KeyStore.Entry entry, long version, boolean deleted) {}

    public synchronized Optional<Entry> find(String alias) throws GeneralSecurityException {
        requireOpen();
        requireAlias(alias);
        EntryVersion latest = findVersion(alias);
        if (latest == null) return Optional.empty();
        return Optional.of(new Entry(latest.alias(), readEntry(keyStore, latest.physicalAlias()),
                latest.version(), latest.deleted()));
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
        try {
            Set<String> resources = Set.of();
            writes.lock();
            try {
                if (find(alias).isEmpty()) {
                    resources = applyState(revision(), List.of(new Entry(alias, entry, 1, false)));
                }
            } finally {
                writes.unlock();
            }
            if (!resources.isEmpty()) publishChanged(resources);
        } finally {
            processPendingSourceChange();
        }
    }

    synchronized boolean hasDurableSnapshot() {
        requireOpen();
        return sourceRevision != null;
    }

    private void append(KeyStore target, Entry addition, long kmsVersion) throws GeneralSecurityException {
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
                + ";" + addition.version() + ";" + kmsVersion + ";" + addition.deleted();
        attributes.add(new PKCS12Attribute(ADDITION_ATTRIBUTE_OID, metadata));
        KeyStore.Entry entry = switch (addition.entry()) {
            case KeyStore.PrivateKeyEntry key -> new KeyStore.PrivateKeyEntry(
                    key.getPrivateKey(), key.getCertificateChain(), attributes);
            case KeyStore.SecretKeyEntry secret -> new KeyStore.SecretKeyEntry(secret.getSecretKey(), attributes);
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
        EntryVersion latest = null;
        for (String physical : physicalAliases()) {
            EntryVersion entry = entryVersion(physical);
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
        if (metadata == null) return new EntryVersion(physical, physical, 1, 0, false);
        try {
            String[] fields = metadata.split(";", -1);
            if (fields.length != 4 || (!fields[3].equals("true") && !fields[3].equals("false"))) {
                throw new IllegalArgumentException();
            }
            String alias = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
            long entryVersion = Long.parseLong(fields[1]);
            long kmsVersion = Long.parseLong(fields[2]);
            if (alias.isBlank() || entryVersion < 1 || kmsVersion < 1) throw new IllegalArgumentException();
            return new EntryVersion(physical, alias, entryVersion, kmsVersion, Boolean.parseBoolean(fields[3]));
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

    private record EntryVersion(String physicalAlias, String alias, long version, long kmsVersion, boolean deleted) {}

    public synchronized long revision() {
        requireOpen();
        return revision;
    }

    public void apply(long expectedRevision, List<Entry> additions) throws IOException {
        try {
            Set<String> resources = applyState(expectedRevision, additions);
            if (!resources.isEmpty()) publishChanged(resources);
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
            if (writes.isLocked() || !sourceChangePending) return;
            sourceChangePending = false;
        }
        try {
            Optional<String> current = source.revision();
            if (current.isPresent()) reloadChangedSource(current.orElseThrow());
        } catch (IOException | RuntimeException failure) {
            System.getLogger(KeyMaterialContentStore.class.getName()).log(System.Logger.Level.WARNING,
                    "Cannot refresh material source after save; retaining the committed state", failure);
        }
    }

    private Set<String> applyState(long expectedRevision, List<Entry> additions) throws IOException {
        long nextVersion;
        Set<String> resources = new HashSet<>();
        writes.lock();
        try {
            byte[] serialized;
            String expected;
            KeyStore publication;
            synchronized (this) {
                requireOpen();
                if (revision != expectedRevision) {
                    throw new KeyMaterialStoreConflictException("Key material changed before apply");
                }
                if (additions.isEmpty()) return Set.of();
                nextVersion = Math.incrementExact(expectedRevision);
                try {
                    publication = copyForPublication();
                    for (Entry addition : additions) append(publication, addition, nextVersion);
                    serialized = codec.encode(publication);
                    expected = sourceRevision;
                } catch (GeneralSecurityException failure) {
                    close();
                    throw new IOException("Cannot prepare key material", failure);
                } catch (IOException | RuntimeException failure) {
                    close();
                    throw failure;
                }
            }
            try {
                String saved;
                try {
                    saved = source.write(serialized, expected);
                } finally {
                    Arrays.fill(serialized, (byte) 0);
                }
                synchronized (this) {
                    requireOpen();
                    if (revision < nextVersion) {
                        for (Entry addition : additions) {
                            append(keyStore, addition, nextVersion);
                            resources.add(addition.alias());
                        }
                        sourceRevision = saved;
                        revision = nextVersion;
                    } else {
                        for (String physical : physicalAliases(publication)) {
                            if (!keyStore.containsAlias(physical)
                                    || !sameEntry(readEntry(keyStore, physical), readEntry(publication, physical))) {
                                throw new GeneralSecurityException("Reloaded material does not contain saved history");
                            }
                        }
                    }
                }
            } catch (GeneralSecurityException failure) {
                close();
                throw new IOException("Cannot save key material", failure);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        } finally {
            writes.unlock();
        }
        return resources;
    }

    public void reload(String requestedRevision) throws IOException, GeneralSecurityException {
        Set<String> resources = reloadState(requestedRevision);
        if (!resources.isEmpty()) publishChanged(resources);
    }

    private synchronized Set<String> reloadState(String requestedRevision)
            throws IOException, GeneralSecurityException {
        requireOpen();
        Objects.requireNonNull(requestedRevision, "revision");
        if (Objects.equals(sourceRevision, requestedRevision)) return Set.of();
        KeyStore incoming;
        byte[] bytes = source.read(requestedRevision);
        try {
            incoming = codec.decode(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        for (String physical : physicalAliases()) {
            if (!incoming.containsAlias(physical)
                    || !sameEntry(readEntry(keyStore, physical), readEntry(incoming, physical))) {
                throw new GeneralSecurityException("Key material history was replaced or removed: " + physical);
            }
        }
        Set<String> resources = new HashSet<>();
        Set<String> entryVersions = new HashSet<>();
        long nextRevision = revision;
        for (String physical : physicalAliases(incoming)) {
            EntryVersion entry = entryVersion(incoming, physical);
            if (!entryVersions.add(entry.alias() + ":" + entry.version())) {
                throw new GeneralSecurityException("Duplicate key material version: " + entry.alias());
            }
            if (keyStore.containsAlias(physical)) continue;
            EntryVersion previous = findVersion(entry.alias());
            if (entry.kmsVersion() <= revision
                    || (previous != null && entry.version() <= previous.version())) {
                throw new GeneralSecurityException("Key material revision does not extend active history");
            }
            readEntry(incoming, physical);
            resources.add(entry.alias());
            nextRevision = Math.max(nextRevision, entry.kmsVersion());
        }
        keyStore = incoming;
        sourceRevision = requestedRevision;
        revision = nextRevision;
        return Set.copyOf(resources);
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
            events.publishIfRunning(new KeyMaterialChanged(Set.copyOf(resources)));
        } catch (RuntimeException failure) {
            System.getLogger(KeyMaterialContentStore.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not publish key material change event", failure);
        }
    }

    private KeyStore copyForPublication() throws IOException, GeneralSecurityException {
        byte[] serialized = codec.encode(keyStore);
        try {
            return codec.decode(serialized);
        } finally {
            Arrays.fill(serialized, (byte) 0);
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
            changes.close();
        } finally {
            try {
                codec.close();
            } finally {
                source.close();
            }
        }
    }
}
