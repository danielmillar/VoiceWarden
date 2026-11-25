tasks.register<Exec>("commentCheck") {
    group = "verification"
    description = "Fail on commented-out code, and on banner comments in Java files."
    val targets = if (project == rootProject) {
        listOf(
            rootProject.file("build.gradle.kts").absolutePath,
            rootProject.file("settings.gradle.kts").absolutePath,
            rootProject.file("gradle/project-checks.gradle.kts").absolutePath,
        )
    } else {
        listOf(
            project.file("src").absolutePath,
            project.file("build.gradle.kts").absolutePath,
        )
    }
    commandLine(listOf("python3", rootProject.file("scripts/check-comments.py").absolutePath) + targets)
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn("commentCheck")
    dependsOn(tasks.matching { it.name == "detekt" || it.name == "detektTest" })
}
