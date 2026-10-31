pluginManagement {
    repositories {
        google()
        // Maven Central itself rate-limits GitHub Actions runner IPs with HTTP
        // 429; this Google-run mirror of the same artifacts absorbs that load,
        // so resolution only falls through to mavenCentral() when it can't serve.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "UMtify"
include(":app")
include(":shared")
include(":sharedUi")
