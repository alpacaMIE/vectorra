plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vectorra.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vectorra.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
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

dependencies {
    val usePublishedVectorraAar = providers.gradleProperty("vectorra.sample.usePublishedAar")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

    if (usePublishedVectorraAar) {
        implementation("${project.group}:vectorra-maps:${project.version}")
    } else {
        implementation(project(":vectorra-maps"))
    }
}
