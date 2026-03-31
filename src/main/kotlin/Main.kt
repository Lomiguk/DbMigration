import cli.main as cliMain

/**
 * Точка входа приложения
 * Теперь используется CLI интерфейс
 *
 * Для запуска миграции используйте:
 *   ./gradlew run --args="init"      - анализ схемы
 *   ./gradlew run --args="copy"      - первичная миграция
 *   ./gradlew run --args="sync"      - синхронизация
 *   ./gradlew run --args="status"    - статус миграции
 *   ./gradlew run --args="config-init" - создание конфига
 */
fun main(args: Array<String>) {
    cliMain(args)
}
