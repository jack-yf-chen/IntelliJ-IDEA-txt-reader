plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform")
}

group = "com.chen.reader"
version = "0.11.4"

kotlin {
    jvmToolchain(21)

    sourceSets {
        main {
            kotlin.exclude("**/probe/**")
        }
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.3")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.chen.reader.novel"
        name = "Novel Reader"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
        }

        vendor {
            name = "Chen"
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("261")
    }
}
