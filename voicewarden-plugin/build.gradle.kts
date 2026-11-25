plugins {
    alias(libs.plugins.shadow)
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
