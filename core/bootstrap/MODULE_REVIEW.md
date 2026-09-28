# Module review: core/bootstrap

## 1. Проверка релиза не связывает подпись с ожидаемым ключом

- **Проблема и триггер.** Файл содержит ожидаемый primary key первым и посторонний primary key вторым; artifact подписан вторым. Fingerprint проверяется по первому, затем импортируются оба ключа и принимается успешная подпись второго.
- **Источники и владельцы.** [Fingerprint](src/main/java/pro/deta/orion/ReleaseVerifier.java#L112), [импорт](src/main/java/pro/deta/orion/ReleaseVerifier.java#L154), [проверка exit code](src/main/java/pro/deta/orion/ReleaseVerifier.java#L167), [первый fpr](src/main/java/pro/deta/orion/ReleaseVerifier.java#L192). Реальный вход — CLI `verify`; [тест](src/test/java/pro/deta/orion/ReleaseVerifierTest.java#L45) использует fake runner и один fingerprint.
- **Документированное поведение.** [README](../../README.md#L166) обещает fingerprint checks и временный keyring; expected fingerprint обязателен. [GNU](https://gnupg.org/documentation/manuals/gnupg/Operational-GPG-Commands.html) отдельно определяет ограничение конкретного подписанта.
- **Контракт.** Обязательна подпись ожидаемого primary key либо его допустимого signing subkey. Наличие ожидаемого ключа среди импортированных этого не обеспечивает.
- **Минимальное исправление.** Связать успешную криптографическую проверку с expected primary fingerprint через машинный статус GPG либо импортировать только ожидаемый primary key вместе с subkeys. Проверить отрицательный случай двух primary keys и положительный signing subkey.
- **Альтернативы и последствия.** [GNU --assert-signer](https://gnupg.org/documentation/manuals/gnupg/GPG-Configuration-Options.html) учитывает signing и primary key, но требует установления поддерживаемой версии GPG. [VALIDSIG](https://github.com/gpg/gnupg/blob/master/doc/DETAILS) предоставляет primary fingerprint. Сравнение только signing-subkey fingerprint с primary сломает законные подписи; отказ от нескольких primary keys закрывает этот trigger и сохраняет subkeys.
- **Уверенность.** Высокая по коду и контракту GPG. Ключи не генерировались, exploit не запускался; fake runner не является криптографическим доказательством.
- **Важность / простота.** Важность высокая: нарушена проверка издателя релиза. Простота средняя: локальная правка с корректной поддержкой primary/subkey и проверкой настоящего GPG.

## 2. Ошибка persistent storage незаметно включает memory backend

- **Проблема и триггер.** Например, при `storage.location=env:ORION_REPOSITORY_DIR` и отсутствующей переменной ошибка разрешения storage перехватывается и выбирается память. Если остальные inputs позволяют startup, записи исчезают после restart.
- **Источники и владельцы.** [Публичный open](src/main/java/pro/deta/orion/BootstrapContext.java#L85), [fallback](src/main/java/pro/deta/orion/BootstrapContext.java#L95), реальный [App](src/main/java/pro/deta/orion/App.java#L74). [Filesystem/reopen test](src/test/java/pro/deta/orion/BootstrapContextTest.java#L379) не покрывает ошибочный backend config.
- **Документированное поведение.** [Filesystem storage](../../README.md#L766) и [повторное использование bootstrap commit](../../README.md#L784) документированы; неявный memory fallback — нет.
- **Контракт.** Настроенный persistent backend должен открыться либо сообщить ошибку. Явная test-only передача memory backend остаётся допустимой.
- **Минимальное исправление.** Удалить catch/fallback в публичном `open`, использовать существующее распространение bootstrap ошибки. Сохранить [test-only overload](src/main/java/pro/deta/orion/BootstrapContext.java#L101) и filesystem restart coverage; проверить невалидную storage конфигурацию.
- **Альтернативы и последствия.** Production memory mode вводит новый продуктовый контракт без текущего основания. Поздняя ошибка скрывает исходную причину; успешный fallback теряет persistence.
- **Уверенность.** Высокая по production-пути. Не вся плохая конфигурация обязательно дойдёт до успешного startup: результат зависит от других inputs. Runtime repro не выполнялся.
- **Важность / простота.** Важность высокая из-за возможной потери данных. Простота высокая: удалить ветку и сохранить содержательное bootstrap coverage.

## 3. Неуспешный stop удаляет PID и разрешает restart

- **Проблема и триггер.** После graceful и forced termination процесс остаётся жив, например при отказе завершения или interruption. Второй `waitFor` игнорируется; PID удаляется, возвращается успех, restart запускает новый процесс.
- **Источники и владельцы.** [Stop](src/main/java/pro/deta/orion/OrionServiceManager.java#L75), [restart](src/main/java/pro/deta/orion/OrionServiceManager.java#L97), [ProcessHandle adapter](src/main/java/pro/deta/orion/OrionServiceManager.java#L404), [interrupted wait](src/main/java/pro/deta/orion/OrionServiceManager.java#L420), CLI [App](src/main/java/pro/deta/orion/App.java#L60). [Stop test](src/test/java/pro/deta/orion/OrionServiceManagerTest.java#L38) и [restart test](src/test/java/pro/deta/orion/OrionServiceManagerTest.java#L53) используют сразу прекращающийся процесс.
- **Документированное поведение.** [README](../../README.md#L133) заявляет stop/restart; успешное «stopped, но жив» не описано.
- **Контракт.** Успешный stop означает остановленный процесс. Restart уже содержит барьер по ненулевому результату stop.
- **Минимальное исправление.** После последнего ожидания проверить liveness. Для живого процесса сохранить PID, вывести ошибку и вернуть ненулевой код. Проверить stubborn process и успешную escalation.
- **Альтернативы и последствия.** Больший timeout не гарантирует завершение; бесконечное ожидание нарушает bounded stop. Новые watchdog или состояния не нужны.
- **Уверенность.** Высокая: известный failure result adapter теряется непосредственно в коде. Runtime repro не выполнялся.
- **Важность / простота.** Важность высокая: ложный operational status и второй экземпляр. Простота высокая: локальный барьер и поведенческие сценарии.

## 4. Фоновый запуск теряет bundled Java

- **Проблема и триггер.** В self-contained distribution `bin/orion start` без host Java и `JAVA_CMD` запускает manager bundled runtime, но manager запускает ребёнка через `java` из PATH. При отсутствующем или старом host Java сервис не запускается.
- **Источники и владельцы.** [Dist launcher](src/main/dist/bin/orion#L19) выбирает bundled Java без экспорта; [settingsFrom](src/main/java/pro/deta/orion/OrionServiceManager.java#L164) подставляет `java`; [commandFor](src/main/java/pro/deta/orion/OrionServiceManager.java#L113) использует это значение. [Тест](src/test/java/pro/deta/orion/OrionServiceManagerTest.java#L25) передаёт settings вручную.
- **Документированное поведение.** [README](../../README.md#L114) называет distribution self-contained и [перечисляет bundled runtime](../../README.md#L123).
- **Контракт.** Bundled installation запускает сервис без отдельного Java; явный `JAVA_CMD` override сохраняется.
- **Минимальное исправление.** При отсутствии override выводить Java path из текущего runtime, `java.home/bin/java`; проверить default selection и packaged launch без host Java.
- **Альтернативы и последствия.** Экспортировать выбранный `JAVA_CMD` из launcher — меньшая shell правка, но прямой запуск jar продолжит выбирать runtime из PATH. Оба варианта не требуют новой конфигурации.
- **Уверенность.** Высокая по двум production-путям; packaged smoke test не запускался.
- **Важность / простота.** Важность средняя: сломан заявленный deployment mode. Простота высокая: локальный default либо экспорт существующего значения.
