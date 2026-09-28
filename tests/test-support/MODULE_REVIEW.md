# Module review: tests/test-support

## 1. Ошибки участников pipe scenario теряются

- **Проблема и триггер.** Client assertion/unchecked failure завершает background thread; client IOException и server Exception только логируются. TERMINATED считается успехом. Неверный ответ той же длины в существующем PingPongStreamTest вызывает AssertionError, который не достигает JUnit; transcript round-trip не проверяет правильность ответа.
- **Источники и владельцы.** [Client catch](src/main/java/pro/deta/orion/util/stream/IOTestStreamUtils.java#L45), [server catch/join](src/main/java/pro/deta/orion/util/stream/IOTestStreamUtils.java#L53), [outcome](src/main/java/pro/deta/orion/util/stream/IOTestStreamUtils.java#L64), реальный [assertion](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L37), [caller/round-trip](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L41), [transcript owner](../../core/common/src/main/java/pro/deta/orion/util/stream/RecordingStandardStreams.java#L117).
- **Документированное поведение.** Class comment helper описывает client/server scenario; тест задаёт обязательный Hello response. Разрешения игнорировать callback failures нет.
- **Контракт.** Failure любого участника должен fail вызывающий тест. TERMINATED не означает success. Сохранить один worker и transcript recording.
- **Минимальное исправление.** Передать worker IOException/RuntimeException/AssertionError вызывающему потоку после join, propagate server failures. При failure закрывать pipe endpoints, позволяя другой стороне завершиться. Использовать существующий thread boundary; проверить server IOException и client AssertionError с cause/cleanup.
- **Альтернативы и последствия.** Log/thread-state check не защищают; общий SoftAssertions не ловит IO/unchecked errors. FutureTask на существующем worker либо небольшой result holder локальны; новый executor/service или thread per I/O не нужны.
- **Уверенность.** Высокая по коду и реальному assertion caller; repro не запускался. Не вся server error обязательно проходит: живой зависший client может fail текущий thread-state check.
- **Важность / простота.** Важность высокая: false-green oracle. Простота средняя: проверить propagation и cleanup.

## 2. Transcript replay отключает сравнение в единственном caller

- **Проблема и триггер.** testPingPongStream2 передаёт null SoftAssertions. AssertiveIOClient сравнивает ответ только при non-null; неверные bytes той же длины проходят.
- **Источники и владельцы.** [Сравнение](src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L53), [чтение](src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L40), [sole caller/null](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L48). Полный поиск иных constructors/callers не нашёл.
- **Документированное поведение.** Class comment обещает сравнение server chunks с recorded bytes и executable assertion.
- **Контракт.** Assertiveness replay обязательна; nullable отключение не нужно callers. Сохранить корректный transcript и send/receive порядок.
- **Минимальное исправление.** Убрать nullable mode, всегда сравнивать обычным assertion и обновить caller; сначала обеспечить propagation из №1. Проверить mismatch и premature/truncated response.
- **Альтернативы и последствия.** Non-null SoftAssertions с обязательным assertAll исправляет caller, но оставляет отключаемый oracle. Hard assertion без №1 потеряется в worker. EOF policy требует корректного обращения с underlying read result.
- **Уверенность.** Высокая, проверка статическая. n==-1/continue сейчас не является loop/skip trigger: [StreamUtils](../../core/common/src/main/java/pro/deta/orion/util/stream/StreamUtils.java#L30) бросает на negative length раньше возврата; это отдельный common finding.
- **Важность / простота.** Важность средняя: текущий replay не проверяет ответ. Простота высокая после №1.

## 3. Chunk хранит недостижимый close mode и игнорируемый boolean

- **Проблема и триггер.** Единственный public factory of(String) задаёт flagToClose=false; три других private constructors не вызываются. writeTo всегда возвращает true на доступном пути, оба callers игнорируют result.
- **Источники и владельцы.** [Chunk](src/main/java/pro/deta/orion/util/stream/Chunk.java#L12), [factory](src/main/java/pro/deta/orion/util/stream/Chunk.java#L34), [branch/return](src/main/java/pro/deta/orion/util/stream/Chunk.java#L38); все [callers](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L25). Lombok getters не используются.
- **Документированное поведение.** Close-mode contract не найден. Tests требуют UTF-8 line write/flush; поток закрывается явно в client.
- **Контракт.** Сохранить bytes/newline/flush/IOException; убрать исключительно недоступный режим и неиспользуемый result API.
- **Минимальное исправление.** Удалить flagToClose, три constructors, dead branch/boolean; writeTo сделать void. Сохранить String factory и поведенческие тесты.
- **Альтернативы и последствия.** Новые factories/close enums создадут контракт без consumer; always-true result сохранит фиктивный сигнал. Полный inlining при двух callers расширяет правку без необходимости.
- **Уверенность.** Высокая: все consumers найдены, external compatibility promise отсутствует.
- **Важность / простота.** Важность низкая; простота высокая: механическое удаление без изменения доступного write path.
