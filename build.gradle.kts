plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "cn.jason31416"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.yaml:snakeyaml:2.2")
    implementation(kotlin("reflect"))
    implementation("org.jline:jline:3.30.4")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("cn.jason31416.MainKt")
}

tasks.named<JavaExec>("run") {
    // 让 `./gradlew run` 能把键盘输入转发给程序。
    // 注意：Gradle 给子进程接的是管道而不是 tty，raw mode 用不了，
    // 真正要跑 TUI 请用 `./gradlew installDist` 再执行生成的脚本。
    standardInput = System.`in`
}
