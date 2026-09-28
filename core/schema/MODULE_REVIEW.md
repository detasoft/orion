# Module review: core/schema

## 1. Два утверждения закрепляют случайную структуру JAXB boundary

- **Проблема и триггер.** [Тест](src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java#L48) требует отсутствия XmlRootElement у доменной модели и точного suffix пакета DTO. Перемещение DTO или неиспользуемая reader аннотация ломают тест при сохранённых XML и mapping contracts.
- **Источники и владельцы.** [Translator](src/main/java/pro/deta/orion/schema/orion/OrionXmlV2Translator.java#L56) создаёт JAXB context явно для OrionV2; [mapper](src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java#L49) связывает DTO с domain. Реальные consumers: [ACL](../acl/src/main/java/pro/deta/orion/acl/XmlService.java#L12), [bootstrap](../bootstrap/src/main/java/pro/deta/orion/BootstrapContext.java#L255). Поведение покрывают [mapping roundtrip](src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java#L138), [XML roundtrip](src/test/java/pro/deta/orion/schema/orion/OrionXmlTest.java#L128) и XSD validation того же теста.
- **Документированное поведение.** [README](../../README.md#L400) требует XSD из reader JAXB model. Точный пакет и отсутствие domain annotation не являются документированным контрактом.
- **Контракт.** Сохранить versioned XML, root `<orion>`, schema validation и mapping semantics. Reflection проверки реальных JAXB annotations являются framework contract и остаются.
- **Минимальное исправление.** Удалить только два incidental assertion; production не меняется.
- **Альтернативы и последствия.** Удаление всего метода потеряет полезную проверку root annotation. Дополнительные structural assertions не проверяют поведение; существующего roundtrip coverage достаточно.
- **Уверенность.** Высокая по production reader и consumers; runtime tests не запускались. Production XML failure не заявляется.
- **Важность / простота.** Низкая важность: хрупкость теста. Высокая простота: две строки, автоматическое исправление.
