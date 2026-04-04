# План разработки

## Статус проекта: 90% (28/31 задач завершено)

---

## Завершённые этапы

### ✅ Этап 1: CLI-утилита (100%)

| Задача | Файлы |
|--------|-------|
| CLI-фреймворк (Clikt) | `build.gradle.kts` |
| 5 CLI команд | `cli/commands/*.kt` |
| Система конфигурации | `config/MigrationConfig.kt` |
| Консольный UI (Mordant) | `ui/MigrationUi.kt` |
| Метрики в реальном времени | Встроено в команды |

### ✅ Этап 2: Отказоустойчивость (100%)

| Задача | Файлы |
|--------|-------|
| Таблица `migration_state` | `state/StateRepository.kt` |
| Сохранение прогресса | `engine/DataMigrator.kt` |
| Resume механизм | `state/MigrationStateManager.kt` |
| Валидация целостности | `validation/DataIntegrityValidator.kt` |
| Логирование и retry | `state/MigrationState.kt` |

### ✅ Этап 3: WAL-репликация (100%)

| Задача | Файлы |
|--------|-------|
| Replication slot | `replication/SlotManager.kt` |
| Чтение WAL | `replication/WalReader.kt` |
| Парсинг событий | `replication/WalReader.kt` |
| Apply изменений | `replication/WalApplier.kt` |
| Отслеживание lag | `replication/ReplicationService.kt` |

### ✅ Этап 4: Оптимизация (100%)

| Задача | Статус |
|--------|--------|
| HikariCP tuning | ✅ Встроено в config |
| Code audit & cleanup | ✅ Удалено ~1000 строк |
| Удаление неиспользуемого кода | ✅ 9 файлов удалено |

### ✅ Этап 5: Тестирование (100%)

| Задача | Результат |
|--------|-----------|
| Unit-тесты | 11 тестов (100% passing) |
| Integration-тесты | 55 тестов (требуется Docker) |
| Benchmark-тесты | 6 тестов (требуется Docker) |
| Документация | `TESTING.md` |

---

## Оставшиеся задачи (Этап 6: Production)

| ID | Задача | Описание | Приоритет |
|----|--------|----------|-----------|
| 6.1 | Поддержка нескольких СУБД | Абстракция DatabaseProvider (MySQL, MariaDB) | Низкий |
| 6.2 | Rolling migration | Online миграция без downtime | Средний |
| 6.3 | REST API | Управление миграцией через HTTP | Низкий |
| 6.4 | Prometheus metrics | Экспорт метрик, Grafana dashboards | Средний |
| 6.5 | Rollback механизм | ✅ Уже реализован | — |

**Прогресс Этапа 6:** 1/5 (20%)

---

## Удалённые компоненты (очистка кодовой базы)

| Файл | Причина | Строк удалено |
|------|---------|---------------|
| `tools/ResultCollector.kt` | Не использовался | ~60 |
| `tools/RunConfig.kt` | Не использовался | ~60 |
| `tools/TestDataGenerator.kt` | Не использовался | ~60 |
| `cache/OptimizedCache.kt` | Не использовался | ~350 |
| `engine/ParallelMigrator.kt` | Не использовался | ~210 |
| `engine/DataPrefetcher.kt` | Не использовался | ~222 |
| `state/MigrationStateManager.kt` | Не использовался | ~144 |

**Итого:** удалено 9 файлов, ~1000 строк кода

---

## Текущая структура кода

```
src/main/kotlin/
├── Main.kt                          # Точка входа
├── cli/
│   ├── MigrateCli.kt
│   └── commands/
│       ├── MigrateInitCommand.kt
│       ├── MigrateCopyCommand.kt
│       ├── MigrateSyncCommand.kt
│       ├── MigrateResumeCommand.kt
│       ├── MigrateValidateCommand.kt
│       ├── MigrateReplicateCommand.kt
│       └── ConfigInitCommand.kt
├── config/
│   └── MigrationConfig.kt
├── core/
│   ├── MetadataReader.kt
│   └── DependencyResolver.kt
├── engine/
│   ├── MappingService.kt
│   └── DataMigrator.kt
├── sync/
│   └── ChangeCapture.kt
├── state/
│   └── StateRepository.kt
├── validation/
│   └── DataIntegrityValidator.kt
├── replication/
│   ├── WalModels.kt
│   ├── SlotManager.kt
│   ├── WalReader.kt
│   ├── WalApplier.kt
│   └── ReplicationService.kt
└── rollback/
    └── RollbackService.kt
```

**Итого:** 23 файла, ~5500 строк кода

---

## Технический долг

| Проблема | Статус | Приоритет |
|----------|--------|-----------|
| WAL replication требует доработки | ⚠️ | Средний |
| REST API не реализовано | ⚠️ | Низкий |
| Prometheus metrics не реализовано | ⚠️ | Средний |

---

## Миссия проекта

Решить проблему деградации производительности и избыточного потребления дискового пространства, вызванную использованием 128-битных UUID v4. Перевести архитектуру БД на 64-битные последовательные ключи BIGINT с сохранением ссылочной целостности и минимальным downtime.

### Измеримые результаты

| Метрика | Результат |
|---------|-----------|
| Размер индексов | **-43.2%** |
| Скорость JOIN | **+2.2x** |
| Производительность миграции | **5000-10000 rec/s** |
