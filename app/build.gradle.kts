import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Build-time keys (CI secrets) end up in BuildConfig; users can also enter their own in Settings. */
fun buildKey(name: String): String = (System.getenv(name) ?: project.findProperty(name) as String? ?: "")
    .replace("\\", "\\\\").replace("\"", "\\\"")

/** Release signing: keystore.properties locally, or ANDROID_KEYSTORE_* environment variables on CI. */
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    signingProps.getProperty(prop) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.xuanthanhtnt.xtplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xuanthanhtnt.xtplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TMDB_API_KEY", "\"${buildKey("TMDB_API_KEY")}\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${buildKey("OPENSUBTITLES_API_KEY")}\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${buildKey("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${buildKey("TRAKT_CLIENT_SECRET")}\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"xuanthanhtnt/XT-Player-Android\"")
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "ANDROID_KEYSTORE_FILE")
        if (storeFilePath != null && file(storeFilePath).exists()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the APK is still installable (debug key), but updates between
            // builds from different machines then fail with a signature mismatch.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/module-info.class",
                "module-info.class",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.jellyfin.ffmpeg)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.smbj)
    implementation(libs.dcerpc) {
        exclude(group = "com.hierynomus", module = "smbj")
    }

    testImplementation(libs.junit)
}
