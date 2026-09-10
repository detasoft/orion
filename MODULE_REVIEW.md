# Замечания к инструкциям репозитория

## 3. Ownership в composite не защищает его leaves от повторного выбора

- **Проблема:** composite process/PTY содержит владельца, но его дочерние leaves
  не содержат claims. Алгоритм выбора считает их незанятыми, хотя родитель
  обозначает занятую область работы.
- **Источники:** [composite](docs/plans/current-work/05_native-session-host/03_process-control-and-pty-closure/TASK.md),
  [LIST_PROCESSES](docs/plans/current-work/05_native-session-host/03_process-control-and-pty-closure/01_list-processes.md),
  [PTY_CLOSED](docs/plans/current-work/05_native-session-host/03_process-control-and-pty-closure/02_pty-closed.md),
  [правила выбора](.agents/skills/orion-task-runner/SKILL.md#selection).
  Коммит `670fce16` занял обе исходные задачи: Linux process-tree control и
  process/PTY, причём последняя уже включала listing, адресные сигналы и PTY closure.
  При разделении в `de8715bd` claim остался в composite. Ветка
  `codex/linux-process-tree-control-47c2` на `79386060` содержит дизайн обеих
  областей и реализацию Linux pidfd/cgroup/termination; production-запрос
  `LIST_PROCESSES` и событие `PTY_CLOSED` там ещё не реализованы.
- **Требуемое поведение и контракт:** claim определяется только `Owner:` в теле
  executable leaf; владельцы composites игнорируются. Нельзя повторно занимать
  уже выполняемую работу или считать старый timestamp освобождением claim.
- **Минимальное исправление:** подтвердить, сохраняется ли исходный резерв 47c2
  на оба дочерних leaf, затем согласовать их claims с принятым распределением
  и убрать вводящий в заблуждение claim composite. Отсутствие реализации
  не освобождает исходный claim. Пользователь пока не разрешил его перенос.
- **Последствия и проверка:** выбор задач будет отражать подтверждённое владение.
  Нельзя механически копировать владельца во все leaves или освобождать их без
  проверки. Наследование claims от composite было бы более широким изменением
  модели и сейчас прямо запрещено. Проверить выбор каждого затронутого leaf.
- **Уверенность:** высокая в несоответствии документов и исходном объёме claim;
  актуальное распределение оставшейся работы требует решения владельца или пользователя.
- **Приоритет:** высокий из-за риска параллельного исполнения; исправление
  требует проверки состояния, а не только редактирования текста.
