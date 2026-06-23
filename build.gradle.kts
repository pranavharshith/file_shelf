plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

// Use an external build directory on dev machines to keep the project folder
// clean and avoid OneDrive/Dropbox re-syncing large build artifacts.
// On CI (GitHub Actions sets CI=true) fall back to the standard ./build
// directory, since the runner has no C:/tmp and every agent is ephemeral.
val isCI = System.getenv("CI") != null
if (!isCI) {
    val externalBuildRoot = file("C:/tmp/fileshelf-build")
    layout.buildDirectory.set(file("${externalBuildRoot.absolutePath}/root"))
    subprojects {
        layout.buildDirectory.set(file("${externalBuildRoot.absolutePath}/${project.name}"))
    }
}
