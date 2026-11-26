// The Windows installer is its own small build (plain Kotlin/JVM, no Android SDK needed):
//   ./gradlew -p installer test fatJar
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "installer"
