plugins { kotlin("jvm") }
dependencies {
    testImplementation(project(":platform:common"))
    testImplementation(project(":adapters:postgres"))
    testImplementation(project(":modules:iam"))
    testImplementation(project(":modules:metadata"))
    testImplementation(project(":modules:document-runtime"))
    testImplementation(project(":apps:server"))
    testImplementation(libs.vertx.core)
    testImplementation(libs.vertx.web)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
