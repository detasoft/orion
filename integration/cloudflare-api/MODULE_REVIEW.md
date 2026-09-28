# Module review: integration/cloudflare-api

## 1. Неиспользуемый пустой CloudflareAPI

- **Проблема и триггер.** Публичный CloudflareAPI не содержит поведения/данных и не используется; рядом с рабочим client публикуется второй API-тип без роли.
- **Источники и владельцы.** [Marker](src/main/java/pro/deta/orion/cloudflare/CloudflareAPI.java#L3), [client](src/main/java/pro/deta/orion/cloudflare/CloudflareClient.java#L13), [implementation](src/main/java/pro/deta/orion/cloudflare/CloudflareClientImpl.java#L24), содержательные [тесты](src/test/java/pro/deta/orion/cloudflare/CloudflareClientImplTest.java#L45), реальный [integration caller](../../tests/integration-test/src/integration-test/java/pro/deta/orion/cloudflare/IntegrationCloudflareIT.java#L29). Поиск marker возвращает только declaration.
- **Документированное поведение.** [План DNS](../../docs/plans/tasks/15_dynamic-domains/02_allocation-and-dns.md#L90) требует существующий CloudflareClient; marker и reflective/service binding не предусмотрены.
- **Контракт.** Сохранить DNS operations, wire DTOs и provider boundary; пустой marker не выражает требования.
- **Минимальное исправление.** Удалить CloudflareAPI.java без replacement API и теста отсутствия класса.
- **Альтернативы и последствия.** Facade/alias создают ещё один путь без consumer; narrowing visibility оставляет мёртвый тип.
- **Уверенность.** Высокая: external binary promise и tracked service metadata не найдены. Generated output не собирался.
- **Важность / простота.** Важность низкая: лишняя публичная концепция. Простота высокая: один файл и compilation потребителей.

## 2. Retry-настройки обещаны, но клиент их не читает

- **Проблема и триггер.** Значения maxRetries/retryDelayMillis/maxRetryDelayMillis не влияют на requests/delays. После законченного ошибочного HTTP response, например 500, executeRequest сразу возвращает ошибку. Это фиктивная configurable policy, а не утверждение отсутствия внутренних transport retries OkHttp.
- **Источники и владельцы.** [Поля](src/main/java/pro/deta/orion/cloudflare/config/CloudflareConfig.java#L37), [constructor](src/main/java/pro/deta/orion/cloudflare/CloudflareClientImpl.java#L33), [request/error path](src/main/java/pro/deta/orion/cloudflare/CloudflareClientImpl.java#L181), [одиночный error test](src/test/java/pro/deta/orion/cloudflare/CloudflareClientImplTest.java#L116). [Integration caller](../../tests/integration-test/src/integration-test/java/pro/deta/orion/cloudflare/IntegrationCloudflareIT.java#L29) использует defaults и DNS POST. Consumers retry accessors не найдены.
- **Документированное поведение.** Javadoc обещает retries и initial/max delay. [Allocation plan](../../docs/plans/tasks/15_dynamic-domains/02_allocation-and-dns.md#L140) требует будущий persisted retriable failure, не конкретный client retry algorithm.
- **Контракт.** Сохранить DNS request/response/auth и native deadlines. Поддержка обещанной retry policy либо удаление настройки требует решения; повтор мутаций нельзя добавлять молча.
- **Минимальное исправление.** После решения предпочтительно удалить три inert fields/accessors/comments, сохранив текущий traffic. Если retries нужны, реализовать bounded policy в executeRequest для явно разрешённых operations/failures с request-count/delay tests.
- **Альтернативы и последствия.** Удаление сужает API без изменения traffic. Реализация меняет latency/request count и требует решить идемпотентность createZone/createDnsRecord POST; ambiguous failure может привести к повторному созданию ресурса. Новый retry service/state не нужен.
- **Уверенность.** Высокая: поля не читаются, client/consumers проверены. Runtime requests не запускались; текущего runtime wiring нет, integration caller и planned boundary существуют.
- **Важность / простота.** Важность средняя: ложный documented configuration contract. Простота высокая при удалении, средняя при реализации.
