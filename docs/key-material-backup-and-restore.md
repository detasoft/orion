# Key Material Backup and Restore

Orion's bootstrap configuration selects the material location, password reference,
active and retained key aliases, and the `orion.xml` source. These inputs must be
restored as a matching generation. The material store contains previous keys even
after their aliases are retired from active use; rotation does not delete them.

## Entry versions and the KMS revision

KMS has exactly two version concepts:

- **Entry version** is an incrementing number for one material alias. It is part
  of the entry identifier, `KeyMaterialId(alias, version)`, serialized in XML
  references to keys, certificates, or certificate chains.
- **KMS revision** identifies the complete persisted KMS state. For Git storage
  it is the Git commit ID read from the content source. It is the expected
  revision on which a modification is based, not an independent numeric counter.

The following rules govern the KMS contract, references, and persistence:

1. The first entry for an alias has version `1`. Updating that alias appends a
   new entry with its previous version plus one. Existing entry versions are
   not overwritten.
2. The version belongs to the entry, not only to its key pair. Installing or
   renewing a certificate chain also appends a new entry version, even when
   the private key stays the same.
3. An XML reference identifies the explicit alias and numeric entry version.
   Resolving it must not silently select the latest version for that alias.
   Selecting an updated entry requires changing the reference to its new ID.
4. Publishing an updated entry version must change the KMS revision. A changed
   KMS revision does not imply that all entries changed: unrelated entries
   retain their IDs and versions.
5. A modification uses the expected KMS revision for conditional publication
   of the complete next state. The expected revision comes from Git; do not
   manufacture a second application-managed revision for that state.
6. Entry versions and the KMS revision are not interchangeable. Increment an
   entry version numerically; treat the Git revision as an opaque commit ID.
7. Do not maintain a separate material-generation number alongside the entry
   version. References, descriptors, and storage must use the same entry version.

For example, Git revision `R1` may contain `(server, 1)` and `(root, 3)`.
Renewing the certificate chain for `server` appends `(server, 2)` and publishes
Git revision `R2`. The root entry remains `(root, 3)`. Configuration that should
use the renewed chain must select `(server, 2)` explicitly.

Descriptors and stored entries use the same entry version. Version ordering is
meaningful only within one alias; configuration selects active and retained
materials without comparing versions across aliases. The whole-store revision
comes from the content source.

## Capture a generation

1. Quiesce writers to the material store, bootstrap configuration, and versioned
   `orion.xml` source. Record their locations, refs, and exact revisions. If they
   are in one Git repository, pin the commit containing both files; if they are
   independent, record each revision separately.
2. Copy the complete protected material-store bytes, the exact bootstrap
   configuration (or its immutable release artifact), and the selected
   `orion.xml` snapshot. Keep the material password or recovery credential in a
   separate protected channel; the password reference alone is not a backup.
3. Re-read the recorded revisions or content hashes after copying. Retry the
   capture if any source changed. Keep prior complete generations; an individual
   file copy or a mutable branch name is not a consistent backup pair.

There is no cross-store atomic commit. A material revision may precede the
configuration revision that activates it; this is safe because the previous
configuration still selects its existing keys. A configuration that references
material not yet present must not be activated.

## Restore a generation

1. Restore the selected material bytes first, without generating replacement
   keys. Preserve the store's protected file permissions or remote access policy.
   Do not pass `--create-if-missing` during recovery: it is only for deliberate
   first-time provisioning.
2. Restore the matching bootstrap configuration and pinned `orion.xml` snapshot.
   Validate the pair in an isolated runtime using the same password source and
   without `--create-if-missing`. Check the selected signing identity, retained
   verification aliases, SSH host keys, and any configured TLS/decryption
   material before promoting the restored sources.
3. Restart against the validated pair. If validation or startup fails, keep the
   current runtime or return its configuration reference to the last valid
   generation; leave all material entries intact. A rollback changes which
   aliases are used, not the physical contents of the material store.

Bootstrap currently rejects missing referenced material before constructing the
runtime. Atomic publication and live replacement of complete `orion.xml`
projections are separate hierarchical-configuration work; this procedure does
not assume that feature already exists.
