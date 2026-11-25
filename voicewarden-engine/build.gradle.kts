plugins {
    alias(libs.plugins.shadow)
}

val sherpaVersion = libs.versions.sherpa.onnx.get()

fun currentSherpaNativeArtifact(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("linux") -> if (isArm) "sherpa-onnx-native-lib-linux-aarch64" else "sherpa-onnx-native-lib-linux-x64"
        os.contains("mac") || os.contains("darwin") -> if (isArm) "sherpa-onnx-native-lib-osx-aarch64" else "sherpa-onnx-native-lib-osx-x64"
        os.contains("win") -> if (isArm) "sherpa-onnx-native-lib-win-arm64" else "sherpa-onnx-native-lib-win-x64"
        else -> "sherpa-onnx-native-lib-linux-x64"
    }
}

repositories {
    ivy {
        name = "sherpa-onnx-github"
        url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
        patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
        }
        metadataSources {
            artifact()
        }
        content {
            includeGroup("com.k2fsa.sherpa.onnx")
        }
    }
    exclusiveContent {
        forRepository {
            ivy {
                name = "sherpa-onnx-models"
                url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                patternLayout {
                    artifact("[revision]/[artifact].[ext]")
                }
                metadataSources {
                    artifact()
                }
            }
        }
        filter {
            includeGroup("com.k2fsa.sherpa.onnx.models")
        }
    }
}

val vadTestAssets: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(project(":voicewarden-core"))
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaVersion@jar")
    implementation(libs.commons.compress)
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    testRuntimeOnly("com.k2fsa.sherpa.onnx:${currentSherpaNativeArtifact()}:$sherpaVersion@jar")
    vadTestAssets("com.k2fsa.sherpa.onnx.models:silero_vad:asr-models@onnx")
    vadTestAssets("com.k2fsa.sherpa.onnx.models:Obama:asr-models@wav")
}

tasks.test {
    inputs.files(vadTestAssets)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val files = vadTestAssets.files
        listOf(
            "-Dvoicewarden.test.sileroVad=${files.single { it.name.startsWith("silero_vad") }.absolutePath}",
            "-Dvoicewarden.test.speechWav=${files.single { it.name.startsWith("Obama") }.absolutePath}",
        )
    })
}

tasks.shadowJar {
    archiveBaseName.set("voicewarden-engine")
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "dev.voicewarden.engine.VoiceWardenEngineMain"
    }
    mergeServiceFiles()
}
