@file:Suppress("PropertyName", "LocalVariableName")

val logback_version: String by project
val exposed_version: String by project
val hikaricp_version: String by project
val koin_version: String by project
val jline_version: String by project
val swagger_ui_version: String by project
val schema_kenerator_version: String by project

plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
}

group = "subit"
version = "0.0.1"

application {
    mainClass.set("subit.ForumBackendKt")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    // ktor server
    implementation(kotlin("reflect")) // kotlin 反射库
    implementation("io.ktor:ktor-server-core-jvm") // core
    implementation("io.ktor:ktor-server-netty-jvm") // netty
    implementation("io.ktor:ktor-server-auth-jvm") // 登陆验证
//    implementation("io.ktor:ktor-server-auth-jwt-jvm") // jwt登陆验证
    implementation("io.ktor:ktor-server-content-negotiation") // request/response时反序列化
    implementation("io.ktor:ktor-server-status-pages") // 错误页面(异常处理)
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-cors-jvm") // 跨域
    implementation("io.ktor:ktor-server-rate-limit-jvm") // 限流
    implementation("io.ktor:ktor-server-websockets") // websocket
    implementation("io.ktor:ktor-server-auto-head-response-jvm") // 自动响应HEAD请求
    implementation("io.ktor:ktor-server-double-receive-jvm") // 重复接收
    implementation("io.ktor:ktor-server-call-logging-jvm") // 日志

    // ktor client
    implementation("io.ktor:ktor-client-core-jvm") // core
    implementation("io.ktor:ktor-client-java")
    implementation("io.ktor:ktor-client-content-negotiation") // request/response时反序列化

    // ktor common
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm") // json on request/response

    // utils
    implementation("io.github.smiley4:ktor-swagger-ui:$swagger_ui_version") // 创建api页面
    implementation("io.github.smiley4:schema-kenerator-core:$schema_kenerator_version")
    implementation("io.github.smiley4:schema-kenerator-swagger:$schema_kenerator_version")
    implementation("io.github.smiley4:schema-kenerator-reflection:$schema_kenerator_version")
//    implementation("com.sun.mail:javax.mail:1.6.2") // 邮件发送
    implementation("ch.qos.logback:logback-classic:$logback_version") // 日志
    implementation("net.mamoe.yamlkt:yamlkt:0.13.0") // yaml for kotlin on read/write file
    implementation("io.ktor:ktor-server-config-yaml-jvm") // yaml on read application.yaml
    implementation("org.fusesource.jansi:jansi:2.4.1") // 终端颜色码
    implementation("org.jline:jline:$jline_version") // 终端打印、命令等
//    implementation("at.favre.lib:bcrypt:0.10.2") // bcrypt 单向加密

    //postgresql
    val postgresql_version: String by project
    implementation("org.postgresql:postgresql:$postgresql_version")

    //数据库
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version") // 数据库
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version") // 数据库
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version") // 数据库
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version") // 数据库
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version") // 数据库
    implementation("com.zaxxer:HikariCP:$hikaricp_version") // 连接池


    // koin
    implementation(platform("io.insert-koin:koin-bom:$koin_version"))
    implementation("io.insert-koin:koin-core")
    implementation("io.insert-koin:koin-ktor")
    implementation("io.insert-koin:koin-logger-slf4j")

    implementation("me.nullaqua:BluestarAPI-kotlin-reflect:4.1.0")


    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

tasks.withType<ProcessResources> {
    filesMatching("**/application.yaml") {
        expand(mapOf("version" to version)) {
            // 设置不转义反斜杠
            escapeBackslash = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ktor {
    fatJar {
        allowZip64 = true
        archiveFileName = "YouthWriteBackend.jar"
    }
}