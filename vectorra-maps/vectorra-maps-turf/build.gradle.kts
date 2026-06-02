import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.vectorra.maps.turf"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(libs.gson)
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "vectorra-maps-turf"
                version = project.version.toString()

                pom {
                    name.set("Vectorra Maps Turf")
                    description.set("GeoJSON and Turf-style geometry utilities for Vectorra Maps.")
                    url.set("https://vectorra.local/vectorra-maps")
                    licenses {
                        license {
                            name.set("Proprietary")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("vectorra")
                            name.set("Vectorra")
                        }
                    }
                }
            }
        }
    }
}
