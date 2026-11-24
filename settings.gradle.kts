pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "ztx"
include(":platform:common", ":adapters:postgres", ":modules:iam", ":modules:metadata", ":modules:document-runtime", ":apps:server", ":tests:bdd")
project(":platform").projectDir = file("backend/platform")
project(":adapters").projectDir = file("backend/adapters")
project(":modules").projectDir = file("backend/modules")
project(":apps").projectDir = file("backend/apps")
project(":tests").projectDir = file("backend/tests")
project(":platform:common").projectDir = file("backend/platform/common")
project(":adapters:postgres").projectDir = file("backend/adapters/postgres")
project(":modules:iam").projectDir = file("backend/modules/iam")
project(":modules:metadata").projectDir = file("backend/modules/metadata")
project(":modules:document-runtime").projectDir = file("backend/modules/document-runtime")
project(":apps:server").projectDir = file("backend/apps/server")
project(":tests:bdd").projectDir = file("backend/tests/bdd")
