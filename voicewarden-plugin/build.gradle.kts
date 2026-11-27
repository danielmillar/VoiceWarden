plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.minotaur)
    alias(libs.plugins.hangar.publish)
}

dependencies {
    implementation(project(":voicewarden-api"))
    implementation(project(":voicewarden-core"))

    compileOnly(libs.paper.api)
    compileOnly(libs.voicechat.api)
    compileOnly(libs.plasmovoice.api)
    compileOnly(libs.floodgate.api)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.placeholderapi)

    testImplementation(libs.paper.api)
    testImplementation(libs.voicechat.api)
    testImplementation(libs.plasmovoice.api)
    testImplementation(libs.floodgate.api)
    testImplementation(libs.luckperms.api)
    testImplementation(libs.placeholderapi)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

val stageEngineBinary by tasks.registering(Sync::class) {
    from(project(":voicewarden-engine").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("generated-engine-resource/META-INF"))
    rename { "voicewarden-engine.jar.bin" }
}

tasks.shadowJar {
    archiveBaseName.set("VoiceWarden")
    archiveClassifier.set("")
    from(stageEngineBinary) {
        into("META-INF")
    }
    relocate("com.zaxxer.hikari", "dev.voicewarden.libs.hikari")
    relocate("io.github.jaredmdobson", "dev.voicewarden.libs.concentus")
    relocate("com.google.gson", "dev.voicewarden.libs.gson")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    mergeServiceFiles()
}

fun csvEnv(name: String) = providers.environmentVariable(name)
    .map { raw -> raw.split(",").map(String::trim).filter(String::isNotEmpty) }
    .orElse(emptyList())

val releaseVersion = project.version.toString()

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set("voicewarden")
    versionNumber.set(releaseVersion)
    versionName.set("VoiceWarden v$releaseVersion")
    versionType.set("release")
    uploadFile.set(tasks.shadowJar)
    loaders.addAll("paper", "folia")
    gameVersions.set(csvEnv("MODRINTH_GAME_VERSIONS"))
    changelog.set(providers.environmentVariable("RELEASE_CHANGELOG").orElse(""))
}

hangarPublish {
    publications.register("plugin") {
        version.set(releaseVersion)
        channel.set("Release")
        id.set("VoiceWarden")
        apiKey.set(providers.environmentVariable("HANGAR_API_TOKEN"))
        changelog.set(providers.environmentVariable("RELEASE_CHANGELOG").orElse(""))
        platforms {
            paper {
                // VoiceWarden's shaded JAR (~45 MB with bundled engine) exceeds Hangar's 20 MB upload cap.
                url.set(
                    "https://github.com/danielmillar/VoiceWarden/releases/download/" +
                        "v$releaseVersion/VoiceWarden-$releaseVersion.jar",
                )
                platformVersions.set(csvEnv("HANGAR_PAPER_VERSIONS"))
                dependencies {
                    hangar("SimpleVoiceChat") {
                        required.set(false)
                    }
                }
            }
        }
    }
}
