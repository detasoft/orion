# Module review: core/common

## 7. Экспериментальный OpenSSH parser живёт ради print-only теста

- **Проблема и триггер.** OpenSSHKey использует ByteArrayMap; иных consumers у пары нет. Единственный тест декодирует fixture и печатает объект без содержательного assertion.
- **Источники и владельцы.** [Parser](src/main/java/pro/deta/orion/util/OpenSSHKey.java#L11), [map](src/main/java/pro/deta/orion/util/ByteArrayMap.java#L10), [тест](src/test/java/pro/deta/orion/util/rle/RLETCoderTest.java#L34). Живой RLE формат: [encode](src/main/java/pro/deta/orion/crypto/OrionPasswordHashingService.java#L55), [decode](src/main/java/pro/deta/orion/crypto/OrionPasswordHashingService.java#L129).
- **Документированное поведение.** Текущая обязанность parser не найдена; действующие SSH paths используют SSH/crypto библиотеки.
- **Контракт.** Сохранить RLETCoder и persisted salt/hash format, ByteBufferUtil и действующие SSH providers.
- **Минимальное исправление.** Удалить пару типов и только testReadingContainer с его fixture/import; сохранить другие RLE tests.
- **Альтернативы и последствия.** Исчезает неполный parser без production consumers. Исправлять его формат вместо удаления не требуется; EdDSA provider сохраняется.
- **Уверенность.** Высокая по всем найденным потребителям.
- **Важность / простота.** Низкая/средняя важность; высокая простота, сокращение двух типов.

## 8. TimeoutReader не ограничивает неполную строку

- **Проблема и триггер.** Поток выдаёт символ без newline и остаётся открыт. После ready()==true readLine блокируется; deadline больше не проверяется. Cleanup OpenSSL процесса находится после чтения.
- **Источники и владельцы.** [Reader](src/main/java/pro/deta/orion/util/TimeoutReader.java#L22), [первое чтение](../../tests/integration-test/src/integration-test/java/pro/deta/orion/comm/handler/OrionDTLSOpenSSLIT.java#L74), [повтор/cleanup](../../tests/integration-test/src/integration-test/java/pro/deta/orion/comm/handler/OrionDTLSOpenSSLIT.java#L82).
- **Документированное поведение.** Отдельной prose-спецификации нет; API явно задаёт initial/extension timeout и читает продолжающий работать процесс.
- **Контракт.** Истечение срока завершает чтение даже без newline; полный обычный вывод сохраняется.
- **Минимальное исправление.** Читать только готовые символы с проверкой deadline между чтениями. Определить возврат неполного хвоста; проверить обычные строки, открытый partial-line stream и отсутствие данных.
- **Альтернативы и последствия.** Thread per I/O запрещён [RULES](../../docs/reviews/RULES.md#L13) и не нужен. Ослабление timeout оставляет зависание; перенос в test support не исправляет логику.
- **Уверенность.** Высокая по блокировке; сценарий не запускался. Воздействие установлено для integration test, runtime production consumers не найдены.
- **Важность / простота.** Средняя важность из-за зависания проверки; средняя простота из-за контракта частичной строки.


## 9. StreamUtils бросает исключение вместо возврата EOF

- **Проблема и триггер.** readStreamInto получает EOF (-1) и выполняет ByteBuffer.put(buffer,0,-1) до return, вызывая IndexOutOfBoundsException. EOF не возвращается на пустом потоке или после последних bytes.
- **Источники и владельцы.** [Метод/read/put](src/main/java/pro/deta/orion/util/stream/StreamUtils.java#L25), [sole consumer](../../tests/test-support/src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L43), который явно проверяет n==-1; [реальный replay test](../../tests/test-support/src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L48). Собственного utility test нет; helper сейчас также теряет этот unchecked worker failure.
- **Документированное поведение.** Javadoc отсутствует. EOF return установлен фактическим caller и возвратом underlying read result; текущий infinite loop не утверждается.
- **Контракт.** EOF возвращается как -1 без изменения buffer position/content. Успешные bytes добавляются; IOException сохраняется. Replay policy при premature EOF принадлежит test-support.
- **Минимальное исправление.** Guard n<0 перед put; проверить empty EOF и EOF после partial read с сохранением buffer position/content. При интеграции проверить реакцию существующего caller на returned EOF.
- **Альтернативы и последствия.** Catch IndexOutOfBoundsException в caller скрывает сломанную utility. Zero EOF меняет read contract и может зациклить do/while. Переписывать общий streaming layer не требуется.
- **Уверенность.** Высокая по negative length и explicit caller; runtime repro не выполнялся. Отдельные buffer-capacity/overread дефекты не утверждаются.
- **Важность / простота.** Важность средняя: реальный replay EOF вызывает неверную ошибку, маскируемую test-support. Простота высокая для utility; consumer EOF policy проверить отдельно.
