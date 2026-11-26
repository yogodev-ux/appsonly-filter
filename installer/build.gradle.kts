plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.appsonly"
version = "0.1.0"

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

application { mainClass.set("com.appsonly.installer.MainKt") }

tasks.test { useJUnitPlatform() }

// One jar with everything inside, for jpackage (Windows .exe) and for the CI test.
tasks.register<Jar>("fatJar") {
    archiveFileName.set("apps-only-setup.jar")
    manifest { attributes["Main-Class"] = "com.appsonly.installer.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}
