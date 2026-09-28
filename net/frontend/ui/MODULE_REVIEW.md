# Module review: net/frontend/ui

## 5. HTTP(S) clone URL не содержит обязательный `.git`

- **Проблема и триггер.** UI показывает и копирует `/r/platform/console`; последующий Git request `/r/platform/console/info/refs` получает 400, поскольку маршрут требует `.git/`.
- **Источники и владельцы.** [cloneUrls](src/App.vue#L185), [отображение](src/App.vue#L589), [clipboard](src/App.vue#L235); транспорт поступает через [API](src/lib/orion-api.js#L162) из [admin route](../../http-core/src/main/java/pro/deta/orion/transport/http/OrionAdminTransportsRoute.java#L40). [Clipboard test](src/App.test.js#L523) закрепляет неверный адрес, [проверка отображения](src/App.test.js#L410) проверяет только substring. Owner — производитель clone URL в UI.
- **Документированное поведение.** [Корневой README](../../../README.md#L801) требует `.git` как границу имени repository. [UI README](README.md#L139) обещает готовую clone command, но [пример](README.md#L145) устарел.
- **Контракт.** Сгенерированные адреса должны соответствовать действующему [HTTP Git route](../../http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L102). Точная старая строка теста не является отдельным контрактом.
- **Минимальное исправление.** Добавить `.git` к HTTP и HTTPS путям в cloneUrls; исправить пример и clipboard test. Проверить полный URL, вложенное/кодируемое имя, оба транспорта и дедупликацию.
- **Альтернативы и последствия.** Один производитель исправляет отображение и clipboard. Ослабление server parser меняет принятый routing contract. Root finding #10 относится к другому производителю адреса и требует отдельной поправки.
- **Уверенность.** Высокая: путь до server rejection прослежен. Реальное клонирование и браузер не запускались.
- **Важность / простота.** Средняя важность: рекламируемая операция клонирования не работает. Локальное поведенческое исправление; обсуждать в Simple по инструкции пользователя.
