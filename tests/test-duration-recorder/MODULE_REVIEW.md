# Module review: tests/test-duration-recorder

## 1. Настроенный путь без каталога отключает запись результатов

- **Проблема и триггер.** При `-Dorion.testDurations.output=durations.jsonl` у пути нет parent. `Files.createDirectories(null)` бросает `NullPointerException`, которое не перехватывается обработчиком `IOException`; результаты test plan не записываются.
- **Источники и владельцы.** [Путь](src/main/java/pro/deta/orion/test/duration/TestDurationRecorder.java#L149), [запись](src/main/java/pro/deta/orion/test/duration/TestDurationRecorder.java#L101); реальные настройки [Surefire](../../pom.xml#L283) и [Failsafe](../../pom.xml#L303). [Launcher-тест](src/test/java/pro/deta/orion/test/duration/TestDurationRecorderTest.java#L26) использует только путь внутри `@TempDir`.
- **Документированное поведение.** Property задана в корневом POM; ограничения на абсолютный путь или наличие каталога не найдено.
- **Контракт.** Настроенный файловый путь должен сохранять результаты; наличие компонента каталога не обязательно.
- **Минимальное исправление.** Создавать parent только при его наличии либо нормализовать configured path через `toAbsolutePath().normalize()`. Проверить basename через настоящий listener.
- **Альтернативы и последствия.** Запрет относительных путей вводит новое ограничение. Проверка parent сохраняет текущее разрешение пути; нормализация унифицирует диагностику. Новые abstractions не нужны.
- **Уверенность.** Высокая, прямой путь выполнения. Подтверждена потеря записи и исключение listener; падение самих тестов не утверждается. Тесты при аудите не запускались.
- **Важность / простота.** Важность средняя: настройка лишает запуск duration-данных. Исправление простое и локальное.

## 2. У параметризованных тестов теряются имя метода и класс в отображаемом ID

- **Проблема и триггер.** Jupiter использует `test-template`, а recorder ищет только `method`. Параметризованные вызовы получают пустой `methodName` и fallback `testId` без класса; реальные записи текущего каталога подтверждают этот путь.
- **Источники и владельцы.** [Извлечение метода](src/main/java/pro/deta/orion/test/duration/TestDurationRecorder.java#L213), [fallback](src/main/java/pro/deta/orion/test/duration/TestDurationRecorder.java#L198), [JFR](src/main/java/pro/deta/orion/test/duration/TestDurationRecorder.java#L130); реальный producer [LocalAccessControlStorageTest](../../connectors/acl-storage/src/test/java/pro/deta/orion/acl/storage/LocalAccessControlStorageTest.java#L447). HTML показывает [module/testId](src/main/java/pro/deta/orion/test/duration/TestAnalyticsReport.java#L970). [Launcher fixture](src/test/java/pro/deta/orion/test/duration/TestDurationRecorderTest.java#L97) содержит только обычный и disabled тесты.
- **Документированное поведение.** Точный формат ID не документирован; схема содержит class/method fields, обычные тесты получают `class#method`. В установленных исходниках Jupiter 5.13.4 подтверждены `test-template` и `test-template-invocation`.
- **Контракт.** Параметризованный вызов должен сохранять доступную идентичность метода и класса. Разбор только `method` — деталь реализации.
- **Минимальное исправление.** Распознавать `test-template` в существующем извлечении метода, сохранив invocation suffix. Добавить настоящий `@ParameterizedTest` в launcher fixture и проверить JSONL/JFR.
- **Альтернативы и последствия.** `MethodSource` предоставляет существующий JUnit API, но переход должен сохранить формат обычных ID. Сохранение fallback оставляет неполные поля и усложняет навигацию.
- **Уверенность.** Высокая: код, исходники Jupiter и реальные записи проверены. Коллизий между классами в одном `(runId,module,testId)` не обнаружено; неверная атрибуция JFR не утверждается.
- **Важность / простота.** Важность средняя: затрагивает массовые запуски и диагностику. Исправление простое, без новых сущностей.

## 3. Display names с CR повреждают структуру CSV

- **Проблема и триггер.** `csvValue` заключает значения в кавычки при comma, quote или LF, но пропускает CR. Реальные параметры `"/item\r"` и `"help\r"` создают display names с неэкранированным CR внутри CSV-записи.
- **Источники и владельцы.** [Quoting](src/main/java/pro/deta/orion/test/duration/TestAnalyticsReport.java#L1202), [запись displayName](src/main/java/pro/deta/orion/test/duration/TestAnalyticsReport.java#L378), реальный producer [InteractiveTerminalTest](../../core/command/src/test/java/pro/deta/orion/command/terminal/InteractiveTerminalTest.java#L396); [тест генератора](src/test/java/pro/deta/orion/test/duration/TestAnalyticsReportTest.java#L16) не покрывает управляющие символы.
- **Документированное поведение.** [README](../../README.md#L893) обещает CSV-артефакты; [Makefile](../../Makefile#L172) запускает генерацию отчётов после JFR-тестов.
- **Контракт.** Значение display name должно сохраняться внутри одной CSV-записи, включая CR.
- **Минимальное исправление.** Добавить CR в условие quoting. Проверить round trip готового CSV для CR, LF, comma и quote через CSV reader.
- **Альтернативы и последствия.** Кавычки вокруг всех полей корректны, но меняют больше выходного текста. Замена CR пробелом теряет данные. Новая зависимость только ради теста необязательна.
- **Уверенность.** Высокая: текущий producer и реальные JSONL-записи проверены. Готовый CSV при аудите не генерировался.
- **Важность / простота.** Важность средняя: обычный отчёт получает разрывы записей или смещение полей. Исправление простое.
