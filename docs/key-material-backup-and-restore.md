# Key Material Backup and Restore

Orion's bootstrap configuration selects the material location, password reference,
active and retained key aliases, and the `orion.xml` source. These inputs must be
restored as a matching generation. The material store contains previous keys even
after their aliases are retired from active use; rotation does not delete them.

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
