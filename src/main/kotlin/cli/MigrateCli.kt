package cli

import cli.commands.MigrateInitCommand
import cli.commands.MigrateCopyCommand
import cli.commands.MigrateSyncCommand
import cli.commands.MigrateResumeCommand
import cli.commands.MigrateStatusCommand
import cli.commands.MigrateValidateCommand
import cli.commands.MigrateReplicateCommand
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
        echo("PostgreSQL UUID to BIGINT Migration Tool")
        echo("")
        echo("Usage: migrate <command>")
        echo("")
        echo("Commands:")
        echo("  init         Analyze schema and build dependency graph")
        echo("  copy         Initial data migration from source to target")
        echo("  sync         Incremental synchronization of new data")
        echo("  resume       Resume interrupted migration")
        echo("  validate     Validate data integrity after migration")
        echo("  replicate    Logical replication via WAL (CDC)")
        echo("  status       Show migration status and metrics")
        echo("  config-init  Create configuration file")
        echo("")
        echo("Use 'migrate <command> --help' for more information")
    }
}

/**
 * Точка входа приложения
 */
fun main(args: Array<String>) {
    println(
        """
        ╔═══════════════════════════════════════════════════════════╗
        ║   PostgreSQL UUID to BIGINT Migration Tool                ║
        ║   Version 1.0.0                                           ║
        ╚═══════════════════════════════════════════════════════════╝
        """.trimIndent()
    )
    println()

    MigrateCli().subcommands(
        MigrateInitCommand(),
        MigrateCopyCommand(),
        MigrateSyncCommand(),
        MigrateResumeCommand(),
        MigrateValidateCommand(),
        MigrateReplicateCommand(),
        MigrateStatusCommand(),
        ConfigInitCommand()
    ).main(args)
}
