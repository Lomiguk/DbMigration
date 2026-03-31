package cli

import cli.commands.MigrateInitCommand
import cli.commands.MigrateCopyCommand
import cli.commands.MigrateSyncCommand
import cli.commands.MigrateStatusCommand
import cli.commands.ConfigInitCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Главная команда CLI приложения
 * Точка входа для всех команд миграции
 */
class MigrateCli : CliktCommand(
    name = "migrate",
    help = "PostgreSQL UUID to BIGINT Migration Tool"
) {
    override fun run() {
        // Команда без подкоманды показывает справку
        echo("PostgreSQL UUID to BIGINT Migration Tool")
        echo("")
        echo("Usage: migrate <command>")
        echo("")
        echo("Commands:")
        echo("  init         Анализ схемы и создание графа зависимостей")
        echo("  copy         Первичный перенос данных из source в target")
        echo("  sync         Инкрементальная синхронизация новых данных")
        echo("  status       Вывод статуса и метрик миграции")
        echo("  config-init  Создание конфигурационного файла")
        echo("")
        echo("Use 'migrate <command> --help' for more information")
    }
}

/**
 * Точка входа приложения
 */
fun main(args: Array<String>) {
    // Приветственное сообщение
    println(
        """
        ╔═══════════════════════════════════════════════════════════╗
        ║   PostgreSQL UUID to BIGINT Migration Tool                ║
        ║   Version 1.0.0                                           ║
        ╚═══════════════════════════════════════════════════════════╝
        """.trimIndent()
    )
    println()

    // Запуск CLI с подкомандами
    MigrateCli().subcommands(
        MigrateInitCommand(),
        MigrateCopyCommand(),
        MigrateSyncCommand(),
        MigrateStatusCommand(),
        ConfigInitCommand()
    ).main(args)
}
