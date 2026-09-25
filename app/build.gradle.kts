import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Third-party binaries: sherpa-onnx Android AAR and the English KWS model.
// Downloaded once from the official k2-fsa release, SHA-256 verified, and
// cached under the Gradle user home so the repository stays source-only.
// ---------------------------------------------------------------------------
val sherpaVersion = "1.13.8"
val sherpaAarSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
// The "-mobile" variant of this release has an encoder that fails at its first Reshape on every
// onnxruntime tested (incl. the one bundled with the AAR); the standard export works.
val kwsModelName = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
val kwsModelSha256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
val depsCache = File(gradle.gradleUserHomeDir, "wakey-deps")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }
    digest().joinToString("") { "%02x".format(it) }
}

fun verifiedDownload(url: String, expectedSha256: String, dest: File): File {
    if (dest.isFile && sha256(dest) == expectedSha256) return dest
    dest.parentFile.mkdirs()
    val partial = File(dest.path + ".part")
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    val actual = sha256(partial)
    if (actual != expectedSha256) {
        partial.delete()
        throw GradleException("Checksum mismatch for $url: expected $expectedSha256, got $actual")
    }
    partial.renameTo(dest)
    return dest
}

val sherpaAar = verifiedDownload(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar",
    sherpaAarSha256,
    File(depsCache, "sherpa-onnx-$sherpaVersion.aar"),
)
val kwsTarball = verifiedDownload(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$kwsModelName.tar.bz2",
    kwsModelSha256,
    File(depsCache, "$kwsModelName.tar.bz2"),
)

val generatedAssets = layout.buildDirectory.dir("generated/wakeyAssets")
val extractKwsModel by tasks.registering(Copy::class) {
    from(tarTree(resources.bzip2(kwsTarball))) {
        include("$kwsModelName/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
        include("$kwsModelName/decoder-epoch-12-avg-2-chunk-16-left-64.onnx")
        include("$kwsModelName/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
        include("$kwsModelName/tokens.txt")
        include("$kwsModelName/bpe.model")
        eachFile { path = "kws/$name" }
        includeEmptyDirs = false
    }
    into(generatedAssets)
}

// ---------------------------------------------------------------------------
// Personal-testing credentials. Read from the git-ignored secrets.properties
// (or environment variables) and compiled into DEBUG builds only. The app
// copies them into Android Keystore-encrypted storage on first launch.
// ---------------------------------------------------------------------------
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.isFile) file.inputStream().use(::load)
}
fun secret(name: String): String = (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Optional: render the Compose UI to PNGs on the JVM with Robolectric's native graphics.
//   ./gradlew testDebugUnitTest -Pwakey.screenshots --tests '*UiScreenshots*'
// Images land in app/build/screenshots. -Pwakey.robolectricRepo=<maven url> picks where Robolectric
// downloads its Android runtime. Off by default, so normal builds download nothing extra.
val screenshots = providers.gradleProperty("wakey.screenshots").isPresent
val robolectricRepo: String? = providers.gradleProperty("wakey.robolectricRepo").orNull

android {
    namespace = "ai.wakey.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.wakey.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.3-jev"
        buildConfigField("String", "SEED_FIREWORKS_API_KEY", quoted(""))
        buildConfigField("String", "SEED_DEEPGRAM_API_KEY", quoted(""))
        buildConfigField("String", "SEED_AIMLAPI_API_KEY", quoted(""))
    }

    buildTypes {
        debug {
            buildConfigField("String", "SEED_FIREWORKS_API_KEY", quoted(secret("FIREWORKS_API_KEY")))
            buildConfigField("String", "SEED_DEEPGRAM_API_KEY", quoted(secret("DEEPGRAM_API_KEY")))
            buildConfigField("String", "SEED_AIMLAPI_API_KEY", quoted(secret("AIMLAPI_API_KEY")))
            // Shrink (not obfuscate) so the sideload APK stays small; see proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = false
        }
    }

    // One APK per CPU family instead of a universal one: most Android 12+ phones need only arm64-v8a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    sourceSets["main"].assets.srcDir(generatedAssets)

    androidResources { noCompress += listOf("onnx", "model") }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
        if (screenshots) {
            unitTests.isIncludeAndroidResources = true
            unitTests.all { test ->
                test.systemProperty("wakey.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.path)
                robolectricRepo?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            }
        }
    }
    if (screenshots) sourceSets["test"].java.srcDir("src/screenshotTest/java")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Sideloaded test builds: compress dex and native libs so the APK is a smaller download.
        dex.useLegacyPackaging = true
        jniLibs.useLegacyPackaging = true
    }
}

tasks.named("preBuild") { dependsOn(extractKwsModel) }

dependencies {
    implementation(files(sherpaAar))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    if (screenshots) testImplementation("org.robolectric:robolectric:4.14.1")
}
