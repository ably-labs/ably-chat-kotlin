import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.build.config) apply false
    alias(libs.plugins.maven.publish) apply false
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

detekt {
    description = "Runs detekt for all modules"
    autoCorrect = true
    config.setFrom(files("${rootProject.rootDir}/detekt.yml"))
    source.setFrom(files(rootProject.rootDir))
}

tasks.detekt.configure {
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/resources/**")
    exclude("**/build/**")
    reports {
        xml.required.set(false)
        html.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(true)
    }
}

tasks.register("check") {
    // register check task for the root project so our detekt task will run on `gradlew check`
}

configure(subprojects) {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(SonatypeHost.S01)
            signAllPublications()
        }
    }
}
