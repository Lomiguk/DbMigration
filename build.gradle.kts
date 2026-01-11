plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.lomiguk"
version = "1.0-SNAPSHOT"

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

    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}