plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.danielmillar"
version = "2.0.0-SNAPSHOT"

base {
    archivesName.set("VoiceWarden")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://maven.maxhenkel.de/repository/public") {
        name = "maxhenkel"
    }
    maven("https://repo.extendedclip.com/releases/") {
        name = "placeholderapi"
        content { includeGroup("me.clip") }
    }
    // sherpa-onnx does not publish its JVM bindings to Maven; resolve the release jar straight from GitHub.
    ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
        name = "sherpa-onnx-releases"
        patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("com.k2fsa.sherpa.onnx") }
    }
}

val sherpaOnnxVersion = "1.13.8"

// The Paper API to compile against: the oldest supported release from gradle.properties unless overridden.
val paperApiVersion = providers.gradleProperty("paperApi").get()
val minecraftVersion = paperApiVersion.substringBefore(".build").removeSuffix(".+")
val pluginApiVersion = minecraftVersion.split('.').take(2).joinToString(".")

dependencies {
    val paperApi = "io.papermc.paper:paper-api:$paperApiVersion"
    compileOnly(paperApi)
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.24")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("me.clip:placeholderapi:2.12.3")
    // Paper ships sqlite-jdbc on the server classpath (checked on 26.1.2 and 26.3), so it is not shaded.
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")

    // Speech-to-text (Java classes only, ~190 KB). Platform native libraries are downloaded at runtime
    // by NativeLibraries so the plugin jar stays small. Must NOT be relocated: JNI symbols are bound to
    // the com.k2fsa.sherpa.onnx package name.
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaOnnxVersion@jar")

    // Tests may use Bukkit's YamlConfiguration, Gson and Adventure from the Paper API.
    testImplementation(paperApi)
    testImplementation("de.maxhenkel.voicechat:voicechat-api:2.6.24")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Compile with the local JDK 27, but restrict APIs and bytecode to Java 25.
    options.release.set(25)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    inputs.property("apiVersion", pluginApiVersion)
    inputs.property("sherpaOnnxVersion", sherpaOnnxVersion)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion, "apiVersion" to pluginApiVersion)
    }
    filesMatching("voicewarden-build.properties") {
        expand("version" to pluginVersion, "sherpaOnnxVersion" to sherpaOnnxVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    // Native-backed integration tests are opt-in: -PsherpaIntegration=/path/to/models
    providers.gradleProperty("sherpaIntegration").orNull?.let { systemProperty("voicewarden.it.models", it) }
    providers.gradleProperty("sherpaNatives").orNull?.let { systemProperty("voicewarden.it.natives", it) }
    providers.gradleProperty("sherpaRecordings").orNull?.let { systemProperty("voicewarden.it.recordings", it) }
    providers.gradleProperty("sherpaModel").orNull?.let { systemProperty("voicewarden.it.model", it) }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// paper-mcp.json loads every VoiceWarden jar in build/libs, so jars left by an earlier version would load twice.
val currentJars = setOf("VoiceWarden-$version.jar", "VoiceWarden-$version-plain.jar")
tasks.withType<Jar>().configureEach {
    val libs = destinationDirectory
    doFirst {
        libs.get().asFile.listFiles { file ->
            file.name.startsWith("VoiceWarden-") && file.name.endsWith(".jar") && file.name !in currentJars
        }?.forEach { it.delete() }
    }
}

tasks.runServer {
    minecraftVersion(minecraftVersion)
}
