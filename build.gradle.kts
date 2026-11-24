plugins { kotlin("jvm") version "2.4.21" apply false }
allprojects {
    group = "com.ztx"
    version = "0.1.0-SNAPSHOT"
}
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("ztx.projectDir", rootProject.projectDir.absolutePath)
    }
}
