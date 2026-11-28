plugins {
    id("java-library")
    kotlin("jvm")
}
dependencies {
    api(project(":platform:common"))
    api(project(":adapters:postgres"))
    api(project(":modules:iam"))
    implementation(libs.vertx.web)
}
