# Module review: integration/cloudflare-api

## 2. Retry-настройки обещаны, но клиент их не читает

- **Проблема и триггер.** Значения maxRetries/retryDelayMillis/maxRetryDelayMillis не влияют на requests/delays. После законченного ошибочного HTTP response, например 500, executeRequest сразу возвращает ошибку. Это фиктивная configurable policy, а не утверждение отсутствия внутренних transport retries OkHttp.
- **Источники и владельцы.** [Поля](src/main/java/pro/deta/orion/cloudflare/config/CloudflareConfig.java#L37), [constructor](src/main/java/pro/deta/orion/cloudflare/CloudflareClientImpl.java#L33), [request/error path](src/main/java/pro/deta/orion/cloudflare/CloudflareClientImpl.java#L181), [одиночный error test](src/test/java/pro/deta/orion/cloudflare/CloudflareClientImplTest.java#L116). [Integration caller](../../tests/integration-test/src/integration-test/java/pro/deta/orion/cloudflare/IntegrationCloudflareIT.java#L29) использует defaults и DNS POST. Consumers retry accessors не найдены.
- **Документированное поведение.** Javadoc обещает retries и initial/max delay. [Allocation plan](../../docs/plans/tasks/15_dynamic-domains/02_allocation-and-dns.md#L140) требует будущий persisted retriable failure, не конкретный client retry algorithm.
- **Контракт.** Сохранить DNS request/response/auth и native deadlines. Поддержка обещанной retry policy либо удаление настройки требует решения; повтор мутаций нельзя добавлять молча.
- **Минимальное исправление.** После решения предпочтительно удалить три inert fields/accessors/comments, сохранив текущий traffic. Если retries нужны, реализовать bounded policy в executeRequest для явно разрешённых operations/failures с request-count/delay tests.
- **Альтернативы и последствия.** Удаление сужает API без изменения traffic. Реализация меняет latency/request count и требует решить идемпотентность createZone/createDnsRecord POST; ambiguous failure может привести к повторному созданию ресурса. Новый retry service/state не нужен.
- **Уверенность.** Высокая: поля не читаются, client/consumers проверены. Runtime requests не запускались; текущего runtime wiring нет, integration caller и planned boundary существуют.
- **Важность / простота.** Важность средняя: ложный documented configuration contract. Простота высокая при удалении, средняя при реализации.
