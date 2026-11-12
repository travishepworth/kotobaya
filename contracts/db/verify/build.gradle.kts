plugins {
    kotlin("jvm") version "2.1.10"
    id("app.cash.sqldelight") version "2.0.2"
}

// Consumers (android/, core/) configure their databases the same way:
// same package names, same srcDirs, default dialect (SQLite 3.18 = Android API 26).
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("kotobaya.db.app")
            srcDirs.setFrom("../sqldelight/app")
        }
        create("RefDatabase") {
            packageName.set("kotobaya.db.ref")
            srcDirs.setFrom("../sqldelight/ref")
        }
    }
}
