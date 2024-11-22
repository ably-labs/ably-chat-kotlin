plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.kotlin)
    alias(libs.plugins.build.config)
}

val version = libs.versions.ably.chat.get()

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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutine.test)
    testImplementation(libs.bundles.ktor.client)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
}
