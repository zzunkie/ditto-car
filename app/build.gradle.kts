import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// release 서명 정보는 저장소 밖(~/.whenever-car-keystore/keystore.properties)에 둔다.
val signingProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.whenever-car-keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.zzunkie.whenever"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zzunkie.whenever"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProps.getProperty("OPENAI_API_KEY", "").trim()}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${localProps.getProperty("OPENAI_MODEL", "gpt-6.1-terra").trim()}\"")
        buildConfigField("String", "OPENAI_REASONING", "\"${localProps.getProperty("OPENAI_REASONING", "none").trim()}\"")
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 문장 해석 평가(ParseEval)에 -PevalParser=llm|local 등을 넘긴다. 키는 테스트가 local.properties 에서 직접 읽는다.
tasks.withType<Test>().configureEach {
    listOf("evalParser", "evalModel", "evalReasoning", "evalLabel").forEach { p ->
        project.findProperty(p)?.let { systemProperty(p, it.toString()) }
    }
    if (project.hasProperty("evalParser")) {
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}

dependencies {
    // Pleos Playground SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
}
