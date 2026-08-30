import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.android.junit)
    // google-services and crashlytics are applied conditionally below: the project must
    // build without a google-services.json so a fresh clone is not blocked on Firebase.
}

// Client configuration is read from a git-ignored .env at the repository root, with an
// environment-variable fallback for CI. Server-only keys (SUPABASE_SERVICE_ROLE_KEY,
// GEMINI_API_KEY) are deliberately absent — those live in Supabase secrets and never
// reach the APK. See .env.example.
//
// Both sources go through Gradle's provider API rather than File.readLines() and
// System.getenv(): those are untracked reads, so with the configuration cache enabled an
// edit to .env would not invalidate the cache and you would silently rebuild with stale
// values.

fun parseDotEnv(text: String): Map<String, String> = text.lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val entry = line.removePrefix("export ").trim()
        val separator = entry.indexOf('=')
        if (separator <= 0) return@mapNotNull null

        val key = entry.substring(0, separator).trim()
        var value = entry.substring(separator + 1).trim()

        // A '#' inside a quoted value is data, not a comment.
        val quoted = value.startsWith("\"") || value.startsWith("'")
        if (!quoted) {
            val comment = value.indexOf(" #")
            if (comment >= 0) value = value.substring(0, comment).trim()
        }

        key to value.removeSurrounding("\"").removeSurrounding("'")
    }
    .toMap()

val dotEnv: Map<String, String> = providers
    .fileContents(rootProject.layout.projectDirectory.file(".env"))
    .asText
    .map(::parseDotEnv)
    .getOrElse(emptyMap())

fun secret(key: String): String =
    dotEnv[key] ?: providers.environmentVariable(key).orNull ?: ""

val hasFirebaseConfig = project.file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.crashlytics.get().pluginId)
}

// ---------------------------------------------------------------------------------------
// LiveKit — the walkie-talkie audio transport.
// ---------------------------------------------------------------------------------------
// LIVEKIT_URL is the self-hosted server's signalling address, e.g. ws://34.100.11.22:7880.
// It lives in the git-ignored .env like every other client value, and never as a literal in
// a Kotlin file: an IP committed to source is one push away from being public, and this one
// points at a machine anybody can then try to join.
//
// It is only an address. The API secret that mints join tokens is NOT here and must never
// be — it lives in Supabase secrets and is used exclusively inside the `livekit-token` edge
// function. A LiveKit secret shipped in an APK is a public secret, and with it anyone can
// mint themselves a token for the emergency channel.
val livekitUrl = secret("LIVEKIT_URL")

// The host alone, for the cleartext exception below. Parsed rather than string-hacked so a
// malformed value yields "" and produces a config with no exception at all, instead of a
// wildcard.
val livekitHost: String = runCatching { URI(livekitUrl).host }.getOrNull().orEmpty()

// ---------------------------------------------------------------------------------------
// Cleartext exception, generated per build, DEBUG ONLY.
// ---------------------------------------------------------------------------------------
// TRADE-OFF, DELIBERATE, NOT FOR PRODUCTION:
//
// The demo server is a bare GCE IP with no domain, so there is no name to put on a TLS
// certificate and signalling runs over ws:// rather than wss://. The LiveKit Android SDK is
// not a browser and does not require a secure transport the way a web client does.
//
// What that costs: the *signalling* channel — the join token, room name, participant
// identities, and speaker events — is readable and tamperable by anyone on the path. What
// it does not cost: the audio itself. WebRTC media is SRTP-encrypted end to end regardless
// of how the peers were introduced, so the voice traffic is not in the clear.
//
// The mitigation is scope. This exception names exactly one host and applies only to the
// debug build type: the release manifest carries no networkSecurityConfig at all, so a
// release APK still refuses cleartext everywhere. Production needs a domain, a certificate,
// and wss://, at which point this whole block is deleted.
//
// Note the floor: android:networkSecurityConfig is honoured from API 24. On an API 23
// device the debug build will fail to reach a ws:// server, and the widget will honestly
// report the radio as unavailable rather than pretending otherwise.
val livekitResDir = layout.buildDirectory.dir("generated/res/livekit").get().asFile

val generateLivekitNetworkConfig = tasks.register("generateLivekitNetworkConfig") {
    description = "Writes a network-security-config permitting cleartext for the LiveKit host only."
    val host = livekitHost
    val outputDir = livekitResDir

    inputs.property("livekitHost", host)
    outputs.dir(outputDir)

    doLast {
        val exception = if (host.isBlank()) {
            "    <!-- LIVEKIT_URL is unset, so no host is excepted and cleartext stays off. -->"
        } else {
            """
            |    <domain-config cleartextTrafficPermitted="true">
            |        <domain includeSubdomains="false">$host</domain>
            |    </domain-config>
            """.trimMargin()
        }

        val xmlDir = File(outputDir, "xml").apply { mkdirs() }
        File(xmlDir, "network_security_config.xml").writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!--
            |    GENERATED by :app:generateLivekitNetworkConfig. Do not edit; edit LIVEKIT_URL
            |    in the repository-root .env instead. Referenced only by src/debug/AndroidManifest.xml.
            |-->
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="false">
            |        <trust-anchors>
            |            <certificates src="system" />
            |        </trust-anchors>
            |    </base-config>
            |$exception
            |</network-security-config>
            |
            """.trimMargin(),
        )
    }
}

android {
    namespace = "com.varisahayak"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.varisahayak"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.varisahayak.HiltTestRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("boolean", "HAS_FIREBASE", "$hasFirebaseConfig")
        buildConfigField("String", "LIVEKIT_URL", "\"$livekitUrl\"")

        manifestPlaceholders["googleMapsApiKey"] = secret("GOOGLE_MAPS_API_KEY")

        resourceConfigurations += listOf("en", "hi", "mr")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            // Minification stays ON. supabase-kt ships no consumer ProGuard rules
            // (contract §0.11 item 5), so proguard-rules.pro carries them instead.
            // If release crashes with SerializationException, add a keep rule — do not
            // disable minification.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // The generated cleartext exception is a debug-only resource. The release variant never
    // sees it, which is what makes "debug build only" a property of the build rather than a
    // promise in a comment.
    sourceSets.getByName("debug").res.directories.add(livekitResDir.absolutePath)

    buildFeatures {
        compose = true
        buildConfig = true // no longer enabled by default in AGP 9
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required at minSdk 23: supabase-kt states an Android minimum of 26 and
        // directs lower targets to enable core library desugaring (contract §0.3).
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }
}

// Replaces `android { kotlinOptions { … } }`, which AGP 9's built-in Kotlin removed.
// jvmTarget is deliberately unset — it defaults to compileOptions.targetCompatibility.
kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

// The generated directory is registered as a plain res root, so every task that reads it
// needs an explicit edge back to the task that fills it — Gradle fails the build rather
// than risk running them out of order.
//
// Every debug task, rather than an enumerated list of resource tasks: AGP changes which
// tasks walk a variant's res roots between versions (processDebugNavigationResources was
// the one missing from the obvious list), and a forgotten edge here is a hard build failure
// every time. There is no cycle to worry about — the generator has no inputs from any other
// task — and the cost of the extra edges is one 300-byte file write.
tasks.matching { it.name.contains("Debug") }.configureEach {
    dependsOn(generateLivekitNetworkConfig)
}

// schemaDirectory is mandatory once the Room Gradle plugin is applied.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- compose ---
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- androidx core ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // --- hilt (KSP, never kapt) ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- room ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- workmanager ---
    implementation(libs.androidx.work.runtime.ktx)

    // --- camerax + ml kit ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.exifinterface)

    // --- maps + location ---
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.maps.compose.widgets)
    implementation(libs.play.services.location)

    // --- firebase ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // --- supabase ---
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    // OkHttp engine, not ktor-client-android: Realtime needs WebSocket support.
    implementation(libs.ktor.client.okhttp)

    // --- livekit (push-to-talk audio) ---
    // Carries a prebuilt WebRTC .so for arm64/armv7/x86/x86_64 transitively; expect the
    // debug APK to grow by roughly 15 MB.
    implementation(libs.livekit.android)

    // --- excel ---
    implementation(libs.poi.ooxml)


    // --- kotlinx ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // --- unit tests: JUnit 5 ---
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // --- instrumented tests: JUnit 4 world (contract §0.9) ---
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.mockk.agent)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
