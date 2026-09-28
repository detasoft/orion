# Module review: core/common

## 9. StreamUtils бросает исключение вместо возврата EOF

- **Проблема и триггер.** readStreamInto получает EOF (-1) и выполняет ByteBuffer.put(buffer,0,-1) до return, вызывая IndexOutOfBoundsException. EOF не возвращается на пустом потоке или после последних bytes.
- **Источники и владельцы.** [Метод/read/put](src/main/java/pro/deta/orion/util/stream/StreamUtils.java#L25), [sole consumer](../../tests/test-support/src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L43), который явно проверяет n==-1; [реальный replay test](../../tests/test-support/src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L48). Собственного utility test нет; helper сейчас также теряет этот unchecked worker failure.
- **Документированное поведение.** Javadoc отсутствует. EOF return установлен фактическим caller и возвратом underlying read result; текущий infinite loop не утверждается.
- **Контракт.** EOF возвращается как -1 без изменения buffer position/content. Успешные bytes добавляются; IOException сохраняется. Replay policy при premature EOF принадлежит test-support.
- **Минимальное исправление.** Guard n<0 перед put; проверить empty EOF и EOF после partial read с сохранением buffer position/content. При интеграции проверить реакцию существующего caller на returned EOF.
- **Альтернативы и последствия.** Catch IndexOutOfBoundsException в caller скрывает сломанную utility. Zero EOF меняет read contract и может зациклить do/while. Переписывать общий streaming layer не требуется.
- **Уверенность.** Высокая по negative length и explicit caller; runtime repro не выполнялся. Отдельные buffer-capacity/overread дефекты не утверждаются.
- **Важность / простота.** Важность средняя: реальный replay EOF вызывает неверную ошибку, маскируемую test-support. Простота высокая для utility; consumer EOF policy проверить отдельно.
