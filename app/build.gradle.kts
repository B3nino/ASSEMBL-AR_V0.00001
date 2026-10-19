plugins {
    alias(libs.plugins.android.application)
}

// Every delivered APK identifies its exact source: client recordings and trial logs must
// never have to be matched to a build by appearance again. The ASSEMBL-AR repository one level
// above the Gradle root tracks the app. Always resolve source identity from that repository.
val sourceRepository: File? = listOf(
    rootProject.projectDir.parentFile,
    rootProject.projectDir,
).firstOrNull { File(it, ".git").exists() }

val isGitInstalled: Boolean by lazy {
    val path = System.getenv("PATH") ?: return@lazy false
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val extensions = if (isWindows) listOf(".exe", ".cmd", ".bat", ".com", "") else listOf("")
    path.split(File.pathSeparator).any { dir ->
        val folder = File(dir)
        folder.isDirectory && extensions.any { ext ->
            val file = File(folder, "git$ext")
            file.isFile && (isWindows || file.canExecute())
        }
    }
}

fun gitOutput(vararg args: String): String {
    val repo = sourceRepository ?: return ""
    if (!isGitInstalled) return ""
    return try {
        providers.exec {
            workingDir(repo)
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) {
        ""
    }
}

val gitCommitCount = gitOutput("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val gitCommit = gitOutput("rev-parse", "--short=10", "HEAD").ifEmpty { "unknown" }
val gitCommitTime = gitOutput("log", "-1", "--format=%cI").ifEmpty { "unknown" }
// New, untracked source files are compiled too; they must never receive a clean commit stamp.
val gitDirty = gitOutput("status", "--porcelain", "--untracked-files=normal").isNotEmpty()

android {
    namespace = "dev.chyken.assembl_ar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.chyken.assembl_ar"
        minSdk = 29
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = "1.1.$gitCommitCount-$gitCommit" + if (gitDirty) "-dirty" else ""
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_COMMIT_TIME", "\"$gitCommitTime\"")
        buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            // Package only the native libraries required by each target ABI.
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        create("partsTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".partstest"
            versionNameSuffix = "-parts-test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.opencv)
    implementation(libs.recyclerview)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
