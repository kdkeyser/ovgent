plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(24)
}

application {
    mainClass.set("be.delijn.verkenner.MainKt")
}

dependencies {
    implementation(libs.graphhopper.core)
    implementation(libs.h3)
    implementation(libs.commons.csv)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.shadowJar {
    archiveBaseName.set("pipeline")
    archiveClassifier.set("")
    mergeServiceFiles()
}
