# Module review: core/bootstrap

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
