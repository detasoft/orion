# Module review: agent-protocol

## 11. Неиспользуемый unsigned-32 decoder в private Fields

- **Проблема и триггер.** Fields хранит unsignedInt decoder, который не вызывается ни одним message field; остаётся недостижимый числовой контракт.
- **Источники и владельцы.** [Helper](src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java#L627), [private owner](src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java#L537); действующие поля используют unsignedShort/nonNegativeInt/signedInt/operationSequence. [Codec test](src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java#L32), [unsigned sequence](src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java#L161), [limits](src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java#L184). Внешние codec callers получают целые messages, private helper им недоступен; поиск unsignedInt возвращает только definition.
- **Документированное поведение.** [Protocol README](protocol/README.md#L19) определяет v1 encoding и full-u64 sequence; unsigned32 field/extension hook не предусмотрен.
- **Контракт.** Сохранить unsigned64 sequence/EventId, signed/ushort validation, limits и wire fixtures.
- **Минимальное исправление.** Удалить только unsignedInt без изменения DTOs/consumers/limits и новых abstractions.
- **Альтернативы и последствия.** Переключение текущего поля на helper изменит wire range без требования; сохранять для будущего поля не требуется. Public binary compatibility не меняется.
- **Уверенность.** Высокая: private class и единственный definition. Runtime tests не запускались.
- **Важность / простота.** Важность низкая: dead code. Простота высокая: один метод и существующие codec/fixture checks.
