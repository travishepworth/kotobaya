// Stand-alone build used only by contracts/db/check.sh to prove the .sq files
// compile with SQLDelight. Not part of the root build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kotobaya-db-contract-verify"
