plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "repairgent"
version = "1.0"

repositories {
    mavenCentral()
}

val koogVersion = "1.0.0"

dependencies {
    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:prompt-llm:$koogVersion")
    implementation("ai.koog:prompt-executor-model:$koogVersion")
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
    implementation("ai.koog:prompt-executor-anthropic-client:$koogVersion")
    implementation("ai.koog:prompt-executor-google-client:$koogVersion-beta")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.example.MainKt")
}

kotlin {
    jvmToolchain(21)
}
