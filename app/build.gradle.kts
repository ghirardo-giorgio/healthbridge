plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oberon.healthbridge"

    // 36 e non 35 come MacroCam: connect-client 1.1.0 rifiuta di essere
    // compilata contro meno. E' solo la versione delle API disponibili al
    // compilatore — targetSdk resta 34, cioe' l'app non chiede al sistema
    // nessuno dei comportamenti nuovi di Android 15 e 16.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oberon.healthbridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes { release { isMinifyEnabled = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Su Android 14 Health Connect sta nel sistema, nell'apex
    // com.android.healthfitness: questa libreria e' il guscio che da API 34 in
    // su chiama direttamente android.health.connect.HealthConnectManager. La si
    // tiene lo stesso perche' e' l'unico posto dove l'API e' in Kotlin con le
    // suspend, e scrivere a mano executor e OutcomeReceiver per otto tipi di
    // record sarebbe il triplo del codice per la stessa risposta.
    implementation("androidx.health.connect:connect-client:1.1.0")

    // connect-client si porta dietro le coroutines ma non le espone a chi la
    // usa, e le sue API sono tutte suspend: senza questa riga `runBlocking`
    // non esiste. La versione la allinea comunque Gradle a quella che la
    // libreria si aspetta.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Solo l'encoder: serve a disegnare il QR con cui il telefono consegna il
    // proprio indirizzo e la chiave, che a mano sarebbero venti caratteri da
    // copiare da uno schermo all'altro senza sbagliarne uno.
    implementation("com.google.zxing:core:3.5.3")
}
