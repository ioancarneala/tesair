import java.net.URI

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

// Download-only access is intentionally shared by installations; DNS/renewal credentials stay on the operator's server.
val certificateEndpoint = providers.environmentVariable("BROWSER_CERTIFICATE_ENDPOINT").getOrElse("").trim()
val certificateDownloadToken = providers.environmentVariable("BROWSER_CERTIFICATE_DOWNLOAD_TOKEN").getOrElse("").trim()
require(certificateEndpoint.isBlank() == certificateDownloadToken.isBlank()) {
    "Configure both browser certificate download settings, or neither."
}
if (certificateEndpoint.isNotBlank()) {
    val endpoint = URI(certificateEndpoint)
    require(endpoint.scheme == "https" && endpoint.host == "certificates.tesair.co" &&
        endpoint.port in listOf(-1, 443) && endpoint.userInfo == null &&
        endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.rawPath == "/v1/bundle" &&
        certificateEndpoint.all { it.code in 33..126 && it != '\"' && it != '\\' }) {
        "Browser certificates must use https://certificates.tesair.co/v1/bundle without credentials or query parameters."
    }
    require(certificateDownloadToken.matches(Regex("[A-Za-z0-9_-]{32,256}"))) {
        "Browser certificate download token must contain 32–256 URL-safe characters."
    }
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "BROWSER_CERTIFICATE_ENDPOINT", "\"$certificateEndpoint\"")
        buildConfigField("String", "BROWSER_CERTIFICATE_DOWNLOAD_TOKEN", "\"$certificateDownloadToken\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
