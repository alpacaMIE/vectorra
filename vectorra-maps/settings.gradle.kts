pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val usePublishedVectorraAar = providers.gradleProperty("vectorra.sample.usePublishedAar")
            .map { it.equals("true", ignoreCase = true) }
            .getOrElse(false)
        if (usePublishedVectorraAar) {
            mavenLocal()
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
}

rootProject.name = "vectorra-maps"
include(":vectorra-maps")
include(":vectorra-maps-turf")
include(":vectorra-sample")
