import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("io.ktor.plugin") version "3.5.0"
    application
}

group = "com.local.voicehall"
version = "1.0.0"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    val ktor = "3.5.0"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")

    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.h2database:h2:2.3.232")
    implementation("org.flywaydb:flyway-core:12.8.1")
    implementation("org.flywaydb:flyway-database-postgresql:12.8.1")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ShadowJar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

ktor {
    fatJar {
        archiveFileName.set("voice-hall-ops-api-all.jar")
    }
}
