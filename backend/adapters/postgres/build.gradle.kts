plugins {
    id("java-library")
    kotlin("jvm")
}
dependencies {
    api(project(":platform:common"))
    api(libs.vertx.pg.client)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql.driver)
}
