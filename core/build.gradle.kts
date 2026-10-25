import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Debug images rendered by the tests land here.
    listOf("autofruit.frames", "autofruit.fps").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    System.getProperty("autofruit.writeSeeds")?.let { systemProperty("autofruit.writeSeeds", it) }
    systemProperty("autofruit.debugDir", layout.buildDirectory.dir("debug").get().asFile.absolutePath)
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
