pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                val localProps = java.util.Properties().apply {
                    val file = rootDir.resolve("local.properties")
                    if (file.exists()) file.inputStream().use { load(it) }
                }
                password = localProps.getProperty("MAPBOX_DOWNLOADS_TOKEN")
                    ?: providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
                    ?: ""
            }
        }
    }
}

rootProject.name = "CLAROS HUD System"
include(":app")
 