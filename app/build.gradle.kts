import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.viagaralauncher.visualizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.viagaralauncher.visualizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile = when {
        rootProject.file("keystore.properties").exists() -> rootProject.file("keystore.properties")
        file("keystore.properties").exists() -> file("keystore.properties")
        rootProject.file("app/keystore.properties").exists() -> rootProject.file("app/keystore.properties")
        else -> rootProject.file("keystore.properties")
    }
    val keystoreProperties = Properties()
    val hasKeystore = if (keystorePropertiesFile.exists()) {
        runCatching {
            keystoreProperties.load(keystorePropertiesFile.inputStream())
            !keystoreProperties.getProperty("storeFile").isNullOrBlank()
        }.getOrDefault(false)
    } else {
        false
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
                val storeFilePath = keystoreProperties.getProperty("storeFile") ?: ""
                val candidate1 = file(storeFilePath)
                val candidate2 = rootProject.file(storeFilePath)
                val candidate3 = file("key01.jks")
                val candidate4 = rootProject.file("app/key01.jks")
                storeFile = when {
                    candidate1.exists() -> candidate1
                    candidate2.exists() -> candidate2
                    candidate3.exists() -> candidate3
                    candidate4.exists() -> candidate4
                    else -> candidate1
                }
                storePassword = keystoreProperties.getProperty("storePassword") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasKeystore && signingConfigs.getByName("release").storeFile?.exists() == true) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    debugImplementation(libs.androidx.ui.tooling)
}

tasks.register("compileDaemonDex") {
    description = "Compiles AudioVisualizerDaemon into visualizer_daemon.dex"
    val javaSource = file("src/daemon/dev/viagaralauncher/visualizer/AudioVisualizerDaemon.java")
    val assetsDex = file("src/main/assets/visualizer_daemon.dex")

    inputs.file(javaSource)
    outputs.file(assetsDex)

    doLast {
        if (assetsDex.exists()) {
            println("visualizer_daemon.dex already present in assets.")
            return@doLast
        }

        val outputDir = layout.buildDirectory.dir("daemon_dex").get().asFile
        val classesDir = File(outputDir, "classes")
        outputDir.mkdirs()
        classesDir.mkdirs()
        assetsDex.parentFile.mkdirs()

        val sdkDir = android.sdkDirectory
        val platformsDir = File(sdkDir, "platforms")
        val androidJar = platformsDir.listFiles()
            ?.filter { File(it, "android.jar").exists() }
            ?.maxByOrNull { it.name }
            ?.let { File(it, "android.jar") }
            ?: error("android.jar not found in $platformsDir")

        val buildToolsDir = File(sdkDir, "build-tools")
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val d8Tool = buildToolsDir.listFiles()
            ?.filter { if (isWindows) File(it, "d8.bat").exists() else File(it, "d8").exists() }
            ?.maxByOrNull { it.name }
            ?.let { if (isWindows) File(it, "d8.bat") else File(it, "d8") }
            ?: error("d8 not found in $buildToolsDir")

        val javacCmd = listOf(
            "javac",
            "-source", "17",
            "-target", "17",
            "-cp", androidJar.absolutePath,
            "-d", classesDir.absolutePath,
            javaSource.absolutePath
        )
        val javacProcess = ProcessBuilder(javacCmd).inheritIO().start()
        if (javacProcess.waitFor() != 0) error("Failed to compile AudioVisualizerDaemon.java")

        val classFiles = classesDir.walk().filter { it.extension == "class" }.map { it.absolutePath }.toList()
        val d8Cmd = listOf(
            d8Tool.absolutePath,
            "--min-api", "26",
            "--output", outputDir.absolutePath
        ) + classFiles
        val d8Process = ProcessBuilder(d8Cmd).inheritIO().start()
        if (d8Process.waitFor() != 0) error("d8 failed to generate classes.dex")

        val generatedDex = File(outputDir, "classes.dex")
        if (!generatedDex.exists()) error("classes.dex was not generated by d8")

        generatedDex.copyTo(assetsDex, overwrite = true)
        println("Generated visualizer_daemon.dex: ${assetsDex.length()} bytes")
    }
}

tasks.named("preBuild").configure {
    dependsOn("compileDaemonDex")
}

