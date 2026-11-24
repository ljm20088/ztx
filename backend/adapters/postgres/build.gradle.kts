plugins {
    id("java-library")
    kotlin("jvm")
}
dependencies {
    api(project(":platform:common"))
    api(libs.vertx.pg.client)
    compileOnly(libs.vertx.codegen.api)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql.driver)
}
