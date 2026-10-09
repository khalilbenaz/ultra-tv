import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Single source of truth for the app version: the top-level VERSION file
// (repo root). versionName is read verbatim ("x.y.z") and versionCode is
// DERIVED deterministically as major*10000 + minor*100 + patch. This matches
// the packed-int scheme UpdateChecker.kt uses to compare GitHub release tags,
// so the in-app updater and the build stay in lockstep with one edit.
// 1.0.29 → versionName "1.0.29", versionCode 10029 (> the legacy code 39, so
// installs over existing builds stay monotonic).
val versionFile = rootProject.file("../VERSION")
val appVersionName: String = versionFile.readText().trim()
val appVersionCode: Int = run {
    val parts = appVersionName.split(".").map { it.trim().toInt() }
    require(parts.size >= 3) { "VERSION must be x.y.z, got '$appVersionName'" }
    parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// URL du Worker (configuration cloud + télémétrie). Ce n'est PAS un secret : le
// Worker authentifie chaque appareil par un jeton obtenu à l'appairage. Il n'existe
// donc plus de jeton de télémétrie embarqué dans l'APK. Surcharge au build :
//   -PULTRA_WORKER_URL=https://mon-worker.exemple.workers.dev   ou variable d'environnement.
// Les anciens ULTRA_LOG_URL sont acceptés en repli ; ULTRA_LOG_TOKEN est ignoré.
fun resolveBuildConfigValue(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: default

val ultraWorkerUrl = resolveBuildConfigValue(
    "ULTRA_WORKER_URL",
    resolveBuildConfigValue("ULTRA_LOG_URL", "https://ultratv-config.khalilbenaz.workers.dev"),
)

// Édition : « standard » (app publique) ou « pro » (variante revendeur : -PULTRA_EDITION=pro).
val ultraEdition = resolveBuildConfigValue("ULTRA_EDITION", "standard")

android {
    namespace = "com.ultratv.tv.nativeapp"
    compileSdk = 35

    defaultConfig {
        // Different applicationId during development so it can be installed
        // alongside the existing Capacitor build (com.ultratv.tv).
        applicationId = "com.ultratv.tv.nativeapp"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }

        // URL par défaut du Worker — voir resolveBuildConfigValue() ci-dessus.
        buildConfigField("String", "WORKER_URL", "\"$ultraWorkerUrl\"")
        // Édition déclarée au tableau de bord du compte (en-tête X-Ultra-Edition) ; « pro » dans la variante revendeur.
        buildConfigField("String", "EDITION", "\"$ultraEdition\"")
    }

    // Release signing — reads ULTRA_KEYSTORE / ULTRA_KEYSTORE_PASSWORD /
    // ULTRA_KEY_ALIAS / ULTRA_KEY_PASSWORD env vars (with ULTRA_LINEAGE for the
    // rotation lineage). Falls back to the debug keystore when env vars are
    // missing so a fresh checkout still produces an installable APK in CI / dev.
    // See SECURITY.md for the rotation procedure.
    //
    // AGP doesn't expose signingLineage in the DSL, so we hand-roll a
    // post-build task `signRelease` that re-signs the produced APK with
    // apksigner --lineage. The end result is an APK that carries the
    // proof-of-rotation signing block, allowing it to install over the
    // existing debug-key install without "INSTALL_FAILED_UPDATE_INCOMPATIBLE".
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ULTRA_KEYSTORE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ULTRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ULTRA_KEY_ALIAS")
                keyPassword = System.getenv("ULTRA_KEY_PASSWORD")
                // Rotation lineage is only natively supported by APK Signature
                // Scheme v3 (Android 9 / API 28+). Pre-9 devices would need
                // the OLD signer for v1/v2 — which we don't ship — so we
                // bumped minSdk to 28 and disable v1/v2. Modern Android TV
                // boxes are all on 9+.
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // R8 full-mode: shrinks resources + obfuscates code. ~18 MB → ~8 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // AGP always signs the release output with the debug keystore.
            // The `resignRelease` Gradle task (further down) then re-signs
            // the produced APK with the proper upload key and embeds the
            // rotation lineage via apksigner. This roundabout works because
            // apksigner can't re-sign an APK that already has v3 signatures
            // from the new key with an added lineage — it needs the old
            // (debug) key as the starting point.
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
    }

    // LibVLC pèse ~80 Mo toutes architectures confondues : en release on découpe par ABI
    // (Chromecast HD = armeabi-v7a 32 bits, la plupart des box = arm64-v8a, x86_64 = émulateur).
    // Le debug reste un APK universel pour l'installation directe.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            // APK universel en plus : c'est lui que cherche la mise à jour intégrée (UltraTV-debug.apk).
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
    // Schémas exportés visibles de MigrationTestHelper (Robolectric lit les assets du build debug) ; absents du release.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // Lint Vital runs during assembleRelease and blocks on any "error" severity
    // issue. We're shipping a hobby APK with no Play track, and the errors it
    // raises are typically about resource configurations that don't affect
    // runtime — flip abortOnError off and only fail the build on actual code
    // issues (caught by the compiler).
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

// Export Room schemas so future version bumps can ship verified Migration
// objects (and so the schema history is tracked in VCS under app/schemas).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation("androidx.core:core-splashscreen:1.0.1")
    // Installe le Baseline Profile (src/main/baseline-prof.txt) au premier lancement : compilation AOT des chemins de démarrage.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Télémétrie de fluidité (images saccadées par écran) : voir perf/PerfTelemetry.kt.
    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    implementation(libs.media3.datasource.rtmp)
    implementation("androidx.documentfile:documentfile:1.0.1")
    // QR code du tableau de bord cloud, généré localement (aucun réseau).
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.mediarouter)
    implementation(libs.play.cast.framework)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.tvprovider)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.room.paging)
    // Deuxième moteur de lecture (MPEG-TS difficiles, AC3/E-AC3/DTS, MKV exotiques) et décodeurs audio logiciels FFmpeg pour Media3.
    implementation(libs.libvlc)
    implementation(libs.media3.ffmpeg.decoder)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Tests — runs on the local JVM with Robolectric for Android types we
    // can't easily strip out (android.util.Base64).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    // Migrations Room : MigrationTestHelper lit les schémas exportés (app/schemas) exposés comme assets de test.
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    // Tests d'UI Compose exécutés sur la JVM via Robolectric (createComposeRule / createAndroidComposeRule).
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

/**
 * Post-process release APK with apksigner --lineage so it installs in place
 * over the existing debug-signed release. Only runs when the env vars are
 * set; otherwise it's a no-op (CI / dev keep using the debug fallback).
 */
val resignRelease by tasks.registering {
    dependsOn("assembleRelease")
    // The doLast block holds script-object references (file(), env lookups)
    // that Gradle's configuration cache can't serialize — opt out explicitly.
    notCompatibleWithConfigurationCache("hand-rolled apksigner exec")
    doLast {
        val ks = System.getenv("ULTRA_KEYSTORE") ?: return@doLast
        val ksPwd = System.getenv("ULTRA_KEYSTORE_PASSWORD") ?: return@doLast
        val alias = System.getenv("ULTRA_KEY_ALIAS") ?: return@doLast
        val keyPwd = System.getenv("ULTRA_KEY_PASSWORD") ?: ksPwd
        val lineage = System.getenv("ULTRA_LINEAGE") ?: return@doLast

        // Avec les splits ABI, AGP produit un APK par processeur + l'universel
        // (app-<abi>-release.apk, app-universal-release.apk) : on les re-signe TOUS.
        // Aucun APK trouvé = échec franc, sinon la release part signée avec la clé
        // de debug jetable du runner et la mise à jour échoue (« conflit de package »).
        val apks = file("build/outputs/apk/release").listFiles { f -> f.name.endsWith("-release.apk") }
            ?.sortedBy { it.name }.orEmpty()
        check(apks.isNotEmpty()) { "[resignRelease] aucun APK release dans build/outputs/apk/release" }
        // Locate apksigner — prefer the build-tools that match compileSdk.
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        val buildTools = file("$sdkRoot/build-tools").listFiles()
            ?.sortedByDescending { it.name }
            ?.firstOrNull { File(it, "apksigner").canExecute() }
            ?: error("[resignRelease] apksigner not found under $sdkRoot/build-tools")
        val apksigner = "${buildTools.absolutePath}/apksigner"

        for (apk in apks) {
            val proc = ProcessBuilder(
                apksigner, "sign",
                "--ks", ks,
                "--ks-key-alias", alias,
                "--ks-pass", "pass:$ksPwd",
                "--key-pass", "pass:$keyPwd",
                "--lineage", lineage,
                "--rotation-min-sdk-version", "28",
                "--min-sdk-version", "28",
                "--v1-signing-enabled", "false",
                "--v2-signing-enabled", "false",
                "--v3-signing-enabled", "true",
                "--v4-signing-enabled", "true",
                apk.absolutePath,
            ).inheritIO().start()
            val code = proc.waitFor()
            check(code == 0) { "[resignRelease] apksigner exited with $code on ${apk.name}" }
            println("[resignRelease] APK re-signed with rotation lineage → $apk")
        }
    }
}
