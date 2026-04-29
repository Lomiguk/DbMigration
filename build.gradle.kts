plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "com.lomiguk"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("cli.MigrateCliKt")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dconsole.encoding=UTF-8"
    )
}

repositories {
    mavenCentral()
}

dependencies {
    // Высокопроизводительный драйвер PostgreSQL
    implementation("org.postgresql:postgresql:42.7.1")

    // Пул соединений для стабильной работы под нагрузкой
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Библиотека для работы с графами (построение дерева зависимостей таблиц)
    implementation("org.jgrapht:jgrapht-core:1.5.2")

    // Корутины для параллельной миграции нескольких таблиц
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Exposed (Lightweight SQL library) для инспекции схем
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")

    // Логирование процессов миграции
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // === CLI и UI ===
    // Clikt - фреймворк для создания CLI приложений
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    // Mordant - консольный UI с цветами и прогресс-барами
    implementation("com.github.ajalt.mordant:mordant:2.7.2")

    // === Observability: Micrometer + Prometheus ===
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.5")

    implementation("io.prometheus:prometheus-metrics-exporter-pushgateway:1.3.1")

    // === Тестовые зависимости ===
    // JUnit 5 - фреймворк для тестирования
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.0")

    // Testcontainers - интеграционные тесты с PostgreSQL
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")

    // AssertJ - fluent assertions для читаемых проверок
    testImplementation("org.assertj:assertj-core:3.24.2")

    // MockK - мокирование для Kotlin
    testImplementation("io.mockk:mockk:1.13.8")

    // Kotlin coroutines test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<Test>("benchmarkTest") {
    description = "Runs performance and stress tests."
    group = "verification"

    useJUnitPlatform()

    filter {
        includeTestsMatching("benchmark.*")
    }

    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.test {
    useJUnitPlatform()

    systemProperty("file.encoding", "UTF-8")
    systemProperty("sun.stdout.encoding", "UTF-8")
    systemProperty("sun.err.encoding", "UTF-8")

    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.err.encoding=UTF-8"
    )

    // Настройки для интеграционных тестов
    maxParallelForks = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Увеличиваем heap size для тестов с большими данными
    jvmArgs("-Xmx2G", "-XX:+UseG1GC")
}
kotlin {
    jvmToolchain(17)
}
