dependencies {
    api(project(":voicewarden-api"))
    implementation(libs.concentus)
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    implementation(libs.hikaricp)
    implementation(libs.sqlite.jdbc)
    implementation(libs.mariadb.java.client)
    implementation(libs.mysql.connector.j)
    implementation(libs.slf4j.api)

    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.slf4j.simple)
}
