# Module review: core/parent-reference/reference

## 1. Пустой withDefaults закрепляет несуществующую настройку registry

- **Проблема и триггер.** Любой вызов Builder.withDefaults() возвращает this без изменения registry. Production/README требуют лишний шаг с обещанием defaults, хотя built-ins выполняет evaluator.
- **Источники и владельцы.** [Метод](src/main/java/pro/deta/orion/resource/reference/ResourceResolverRegistry.java#L24), [production constructors](src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolver.java#L43), [built-ins](src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolver.java#L68); содержательные [registry tests](src/test/java/pro/deta/orion/resource/reference/ResourceResolverRegistryTest.java#L9), [scenarios](src/test/java/pro/deta/orion/resource/reference/ResourceReferenceScenarioTest.java#L77), [cross-module caller](../resolvers/src/test/java/pro/deta/orion/resource/reference/resolver/ResourceReferenceCapabilityResolverTest.java#L100), реальный [key-material consumer](../../key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialResourceResolver.java#L37).
- **Документированное поведение.** [README](../README.md#L102) описывает built-in String/Path/content; [пример](../README.md#L159) вызывает withDefaults. Отключение defaults или изменение порядка этим методом не предусмотрено.
- **Контракт.** Сохранить built-ins, explicit extension registration и ambiguity detection; inert marker не является обязательным контрактом.
- **Минимальное исправление.** Удалить withDefaults и все вызовы в production/tests/README; сохранить builder/add/build и поведенческие проверки.
- **Альтернативы и последствия.** Перенос built-ins в registry добавляет ненужное перепроектирование. Alias/no-op/deprecation сохраняют лишний путь. Новые типы, состояние и опции не нужны.
- **Уверенность.** Высокая: полный поиск consumers; внешнего опубликованного compatibility promise не найдено. Runtime tests не запускались.
- **Важность / простота.** Важность низкая: ложная настройка API при корректном текущем результате. Простота высокая: механическое удаление, compilation reference/resolvers/key-material и существующее coverage.
