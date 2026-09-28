# Module review: connectors/acl-storage

## 2. Local save частично публикует несколько документов

- **Проблема и триггер.** При изменении двух и более ACL files первый rename проходит, следующий Files.move бросает IOException. Snapshot уже смешанный; save throws, последующая activation/reload не выполняется.
- **Источники и владельцы.** [Sequential moves/cleanup](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L121), реальный multidocument [resetRootPassword](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L951), [save-before-reload](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1767), [prepare-all test](src/test/java/pro/deta/orion/acl/storage/LocalAccessControlStorageTest.java#L271), [multifile success](src/test/java/pro/deta/orion/acl/storage/LocalAccessControlStorageTest.java#L369).
- **Документированное поведение.** [Snapshot plan](../../docs/plans/tasks/02_hierarchical-orion-configuration/04_acl-storage-hardening/05_exact-snapshot-save.md#L14) оставляет atomic generation либо narrower contract решением.
- **Контракт.** Сохранить snapshot CAS, общий reader/writer lock, per-file atomic replacement, permissions и untouched identical files. Несколько moves не обеспечивают общую transaction/crash durability.
- **Минимальное исправление.** Согласовать all-or-nothing либо explicit partial publication/reconciliation; затем regression failure после первого rename. Prepare всех temp files не решает поздний отказ.
- **Альтернативы и последствия.** Immutable generations меняют operator layout; rollback может сам отказать и не атомарен; запрет Local/multifile writers убирает поддерживаемую возможность.
- **Уверенность.** Высокая по текущему пути; fault/crash repro не выполнялся. Не вся credential operation меняет несколько документов; typed PERSISTENCE_FAILED не приписывается безусловно startup recovery.
- **Важность / простота.** Важность высокая: root recovery может оставить partial durable state. Простота низкая: требуется contract/layout решение.

## 3. Проверки Local paths оставляют directory-swap TOCTOU

- **Проблема и триггер.** Actor с правом rename descendant directory меняет проверенный каталог на symlink до read/open/temp/move. Final NOFOLLOW не защищает intermediate components; операция может выйти за ACL root.
- **Источники и владельцы.** [resolvePath](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L248), [read](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L264), [prepare](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L146), [publication](src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java#L129), реальный [bootstrap consumer](../../core/bootstrap/src/main/java/pro/deta/orion/BootstrapContext.java#L318), [static symlink tests](src/test/java/pro/deta/orion/acl/storage/LocalAccessControlStorageTest.java#L321).
- **Документированное поведение.** [Containment plan](../../docs/plans/tasks/02_hierarchical-orion-configuration/04_acl-storage-hardening/04_local-path-containment.md#L14) требует through-use anchoring, safe creation и outside-root nonaccess.
- **Контракт.** Root trusted и может быть symlink; descendant links отвергаются даже внутри root. Cooperative lock не исключает external directory rename.
- **Минимальное исправление.** Выбрать поддерживаемый directory-handle mechanism для traversal/read/lock/temp/publication, сохранив nested creation и per-file atomic replacement; согласовать платформы и проверить adversarial race.
- **Альтернативы и последствия.** Rechecks сужают окно, но не закрывают гонку. Immutable ancestors меняют deployment contract. SecureDirectoryStream зависит от provider.
- **Уверенность.** Высокая в gap; adversarial race/providers при аудите не запускались. Наличие конкретного macOS/Corretto provider не утверждается.
- **Важность / простота.** Важность высокая при доступных actor mutable descendants. Простота низкая: platform и creation semantics.
