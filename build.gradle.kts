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
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation(kotlin("reflect"))
    implementation("org.jline:jline:3.30.4")
    testImplementation(kotlin("test"))

    // Ktor 服务端
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("io.ktor:ktor-server-websockets:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("io.ktor:ktor-server-cors:3.1.3") // 本地开发前后端端口不同需要

    implementation("org.kotlincrypto.hash:sha2:0.7.0")
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

tasks.register<Exec>("buildFrontend") {
    workingDir("web")
    commandLine("npm", "run", "build")
}

tasks.processResources {
    dependsOn("buildFrontend")
    from("web/dist") {
        into("web")
    }
}