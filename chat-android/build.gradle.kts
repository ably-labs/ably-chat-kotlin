import com.android.build.gradle.tasks.SourceJarTask
import com.github.gmazzo.buildconfig.BuildConfigTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.kotlin)
    alias(libs.plugins.build.config)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
}

val version = property("VERSION_NAME")

android {
    namespace = "com.ably.chat"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

buildConfig {
    packageName("com.ably.chat")
    useKotlinOutput { internalVisibility = true }
    buildConfigField("APP_VERSION", provider { "\"${version}\"" })
}

dependencies {
    api(libs.ably.android)
    implementation(libs.gson)
    implementation(libs.coroutine.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutine.test)
    testImplementation(libs.bundles.ktor.client)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Skip tests for the "release" build type so we don't run tests twice
    if (name.lowercase().contains("release")) {
        enabled = false
    }
}

tasks.withType<SourceJarTask>().configureEach {
    dependsOn(tasks.withType<BuildConfigTask>())
}
