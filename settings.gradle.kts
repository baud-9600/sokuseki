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
        // MMD is consumed from the local Maven repository (~/.m2), as requested.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "sokuseki"
include(":app")
