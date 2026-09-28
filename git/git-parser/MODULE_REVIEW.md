# Module review: git/git-parser

## 16. Неиспользуемое пространство блокировок loose objects

- **Проблема и триггер.** Package-private [lockObject](src/main/java/pro/deta/orion/git/parser/v2/storage/GitLock.java#L54) не имеет production consumers: отдельные объекты публикуются через packs. Сохраняется лишний внутренний контракт блокировки.
- **Источники и владельцы.** Единственный [consumer — тест](src/test/java/pro/deta/orion/git/parser/v2/storage/GitLockTest.java#L63). Реальные owners используют [refs](src/main/java/pro/deta/orion/git/parser/v2/storage/GitRefsStorage.java#L150) и [packs](src/main/java/pro/deta/orion/git/parser/v2/storage/GitPackStorage.java#L298); [writeObject](../git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java#L153) публикует pack.
- **Документированное поведение.** Javadoc GitLock описывает refs/packs/loose namespaces. История 5c3c38ef удалила GitObjectStorage; текущий [план миграции](../../docs/plans/tasks/11_git/13_jgit-to-native-migration.md#L215) импортирует loose objects через packs. Текущего требования native loose publication не найдено.
- **Контракт.** Сохранить refs/pack isolation, exclusion между участниками одного repository, независимость repositories, interruptible waiting и lease release. ObjectId namespace текущим операциям не требуется.
- **Минимальное исправление.** Удалить lockObject и его import, исправить Javadoc. В namespace scenario использовать реальные lockRefs и lockPack, сохранив проверки ожидания и разных repositories.
- **Альтернативы и последствия.** Сохранение метода обслуживает только гипотетическую публикацию. Lock framework, ObjectId, публичный API, wire и persisted contracts не меняются.
- **Уверенность.** Высокая по usages, истории и текущей архитектуре; runtime tests не запускались.
- **Важность / простота.** Низкая важность: мёртвая операция. Высокая простота: локальное автоматическое удаление с адаптацией поведенческого теста.
