plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin) apply false
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

allprojects {
    repositories {
        google()
        mavenLocal()
        mavenCentral()
    }
}

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektAll") {
  description = "Runs detekt for all modules."
  debug = true
  config.setFrom(files("${rootProject.rootDir}/detekt.yml"))
  setSource(files(rootProject.rootDir))
  include("**/*.kt")
  include("**/*.kts")
  exclude("resources/")
  exclude("build/")
}
