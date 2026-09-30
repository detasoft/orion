# Git compatibility exporter

Build the module with `mvn package -Pdev -pl git/git-compat-tool -am`. The runnable
artifact is `git/git-compat-tool/target/git-compat-tool-1.0-SNAPSHOT-all.jar`.

```sh
java -jar git/git-compat-tool/target/git-compat-tool-1.0-SNAPSHOT-all.jar \
  export --store /srv/orion/git --repository project --output /tmp/project.git
```

`--store` is the native repository provider's root directory. The source
repository must stay unchanged during export. The output path must not exist;
its parent directory must exist. The exporter creates a bare SHA-1 Git
repository, validates it with `git fsck --full`, then publishes it at the output
path. If conversion or validation fails, a new output path remains absent.

Tree entries are reordered according to Git's byte ordering. Referencing trees,
commits, tags, and refs receive the corresponding new object IDs. The mapping
from original to exported IDs is written to `orion-export/object-map.tsv` inside
the bare repository. A signed commit or tag is rejected if its signature would
need rewriting. The source repository and its fetch behavior are unchanged.
