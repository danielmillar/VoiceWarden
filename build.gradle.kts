plugins {
    base
    alias(libs.plugins.detekt)
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.voicewarden"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.maxhenkel.de/repository/public")
        maven("https://repo.plasmoverse.com/releases")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

detekt {
    buildUponDefaultConfig = false
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle/project-checks.gradle.kts",
            "voicewarden-api/build.gradle.kts",
            "voicewarden-core/build.gradle.kts",
            "voicewarden-engine/build.gradle.kts",
            "voicewarden-plugin/build.gradle.kts",
        ),
    )
}

apply(from = "gradle/project-checks.gradle.kts")

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        val junitBom = rootProject.libs.junit.bom
        "testImplementation"(platform(junitBom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        "testImplementation"(rootProject.libs.mockito.core)
        "testImplementation"(rootProject.libs.mockito.junit.jupiter)
    }

    apply(from = rootProject.file("gradle/project-checks.gradle.kts"))
}
