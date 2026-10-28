rootProject.name = "outageguard"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("gson", "2.13.1")
            version("slf4j", "2.0.17")
            version("logback", "1.5.18")
            version("lombok", "1.18.42")
            version("junit", "6.0.0")

            library("gson", "com.google.code.gson", "gson").versionRef("gson")
            library("slf4j-api", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("logback-classic", "ch.qos.logback", "logback-classic").versionRef("logback")
            library("lombok", "org.projectlombok", "lombok").versionRef("lombok")
            library("junit-bom", "org.junit", "junit-bom").versionRef("junit")
            library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit-platform-launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()
        }
    }
}
