plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

val vectorraGroup = providers.gradleProperty("VECTORRA_GROUP").get()
val vectorraVersion = providers.gradleProperty("VECTORRA_VERSION").get()

allprojects {
    group = vectorraGroup
    version = vectorraVersion
}
