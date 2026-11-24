plugins {
    id("java-library")
    kotlin("jvm")
    application
}
dependencies {
    implementation(project(":platform:common"))
    implementation(project(":adapters:postgres"))
    implementation(project(":modules:iam"))
    implementation(project(":modules:metadata"))
    implementation(project(":modules:document-runtime"))
    implementation(libs.vertx.core)
    implementation(libs.vertx.web)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}
application { mainClass.set("com.ztx.server.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

// 生产/本地集成启动时，将 Vue 构建产物纳入 Server 的 classpath/webroot。
tasks.processResources {
    val frontendDist = providers.gradleProperty("ztx.frontendDist")
    if (frontendDist.isPresent) {
        from(frontendDist.map { file(it) }) {
            into("webroot")
        }
    }
}
