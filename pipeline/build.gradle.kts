plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.h3)
    implementation(libs.commons.csv)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("run") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("be.delijn.verkenner.MainKt")
    workingDir = rootProject.projectDir
}

tasks.shadowJar {
    archiveBaseName.set("pipeline")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "be.delijn.verkenner.MainKt"
    }
}
