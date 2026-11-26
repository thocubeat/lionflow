pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric's large files (only used by the tests that draw the car screens) come from Google's copy of
        // Maven Central: the main one often refuses such big downloads to build servers.
        exclusiveContent {
            forRepository { maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") } }
            filter { includeGroup("org.robolectric") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "LionFlow"
include(":app")
include(":wear")
