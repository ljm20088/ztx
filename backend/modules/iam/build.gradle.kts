plugins {
    id("java-library")
    kotlin("jvm")
}
dependencies {
    api(project(":platform:common"))
    implementation(project(":adapters:postgres"))
    implementation(libs.vertx.web)
}
