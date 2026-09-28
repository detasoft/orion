# Module review: git/git-native-storage

## 6. UTF-16 сортировка нарушает порядок Git tree

- **Проблема и триггер.** Соседние имена U+E000 и U+10000 сортируются String в порядке, противоположном unsigned UTF-8 bytes. Дефект возникает при новом save и обновлении другого файла в импортированном дереве.
- **Источники и владельцы.** [TreeMap](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L112), [порядок](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L214), [UTF-8](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L276); реальные [proxy save](../git-native-proxy/src/main/java/pro/deta/orion/git/proxy/PolicyBoundNativeGitRepository.java#L62), [ACL save](../../connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java#L82), [adapter](../../tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitWorkTree.java#L171), [Unicode test](src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java#L28).
- **Документированное поведение.** [Официальный Git comparator](https://github.com/git/git/blob/master/tree.c#L93) сравнивает bytes и учитывает directory '/'; [fsck](https://git-scm.com/docs/git-fsck) определяет treeNotSorted как ERROR.
- **Контракт.** Сохранить допустимые Unicode paths, file modes, omitted paths и формат Git tree. Java ordering является incidental.
- **Минимальное исправление.** Локальный unsigned UTF-8 comparator нормализованных полных путей, сохранив группировку по '/'. Расширить [JGit observer](src/test/java/pro/deta/orion/git/nativestorage/NativeGitFileModesTest.java#L122): пара в корне/подкаталоге, a.c/a/x/a0, повторный save другого файла, modes и независимая проверка порядка.
- **Альтернативы и последствия.** Новая production зависимость или service не нужны. Запрет Unicode меняет API и не исправляет уже импортированные деревья. IDs исправленных новых trees закономерно изменятся.
- **Уверенность.** Высокая по статическому пути; closure validator порядок не проверяет. Runtime repro и конкретные проблемные пользовательские имена не установлены.
- **Важность / простота.** Важность средняя: узкий набор имён нарушает persisted format. Локальное исправление, умеренная сложность проверки.

## 7. Конфликт файла и каталога создаёт duplicate tree entries

- **Проблема и триггер.** Существует файл a, update сохраняет a/b без удаления a; обратный переход и одновременный save a+a/b дают дубли имени a: blob и tree. Собственный loader затем не прочитает a/b.
- **Источники и владельцы.** [Merge paths](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L113), [directory](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L223), [file](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java#L235), [первое имя loader](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileLoader.java#L90), [ошибка](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileLoader.java#L69). Public save/prepare callers приведены в №6; [независимый observer](src/test/java/pro/deta/orion/git/nativestorage/NativeGitFileModesTest.java#L133).
- **Документированное поведение.** [API](src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java#L42) сохраняет omitted paths и применяет explicit deletions; [Git fsck](https://git-scm.com/docs/git-fsck) определяет duplicateEntries как ERROR.
- **Контракт.** Нельзя публиковать duplicate names или неявно удалять omitted a/descendants.
- **Минимальное исправление.** После нормализации и explicit deletions отклонять итоговое множество с листом-предком другого листа до pack/ref publication. Проверить оба направления, один request, aliases, сохранение refs/content при отказе и успешные переходы с explicit deletions.
- **Альтернативы и последствия.** Неявная замена поддерева меняет контракт omitted paths. Локальный отказ сохраняет установленную семантику, новая структура не нужна.
- **Уверенность.** Высокая: duplicate emission, отсутствие проверки и first-name loader прослежены. Runtime repro не выполнялся.
- **Важность / простота.** Важность высокая: некорректное хранилище и недоступность успешно сохранённого файла. Простота средняя, отдельный результат от №6.
