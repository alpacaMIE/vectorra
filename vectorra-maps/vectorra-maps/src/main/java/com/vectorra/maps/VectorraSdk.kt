package com.vectorra.maps

object VectorraSdk {
    val VERSION: String = BuildConfig.VECTORRA_VERSION
    val API_STATUS: String = BuildConfig.VECTORRA_API_STATUS
    val MIN_ANDROID_SDK: Int = BuildConfig.VECTORRA_MIN_ANDROID_SDK
    val RENDERER: String = BuildConfig.VECTORRA_RENDERER
}

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY
)
annotation class VectorraBetaApi(val since: String = "0.1.0-beta.1")

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY
)
annotation class VectorraExperimentalApi(val since: String = "0.1.0-beta.1")
