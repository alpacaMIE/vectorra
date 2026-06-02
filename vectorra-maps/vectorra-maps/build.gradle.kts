import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.vectorra.maps"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                val worktreeRoot = rootProject.projectDir
                val vcpkgRoot = worktreeRoot.resolve("build/vcpkg")
                arguments += listOf(
                    "-DCMAKE_TOOLCHAIN_FILE=${vcpkgRoot.resolve("scripts/buildsystems/vcpkg.cmake").absolutePath}",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=${android.ndkDirectory.resolve("build/cmake/android.toolchain.cmake").absolutePath}",
                    "-DVCPKG_INSTALLED_DIR=${worktreeRoot.resolve("build/vcpkg_installed_ndk28").absolutePath}",
                    "-DVCPKG_MANIFEST_MODE=OFF",
                    "-DANDROID_PLATFORM=android-28",
                    "-DANDROID_STL=c++_static",
                    "-DROCKY_SUPPORTS_GDAL=OFF",
                    "-DROCKY_SUPPORTS_MBTILES=OFF",
                    "-DROCKY_SUPPORTS_AZURE=OFF",
                    "-DROCKY_SUPPORTS_IMGUI=ON",
                    "-DROCKY_SUPPORTS_HTTPS=OFF",
                    "-DROCKY_SUPPORTS_HTTPLIB=OFF",
                    "-DROCKY_SUPPORTS_QT=OFF",
                    "-DROCKY_BUILD_SHARED_LIBS=ON"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.vectorra.maps"
                artifactId = "vectorra-maps"
                version = "0.1.0"
            }
        }
    }
}
