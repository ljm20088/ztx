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
