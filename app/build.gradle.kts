import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xiangqi.assist"
    compileSdk = 34
    buildToolsVersion = "34.0.0" // 本机 SDK 仅 34.0.0 的 build-tools 完整可用
    // 说明：原工程用 ndkBuild 编译 libnativeutil.so。本机构建环境无匹配 NDK 工具链，
    // 改为预先用 NDK r28b 的 sysroot + 主机 clang 交叉编译好 jniLibs/*/libnativeutil.so，
    // 因此这里不再声明 externalNativeBuild / ndkVersion。
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.xiangqi.assist"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        versionName = "1.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources.excludes.addAll(
            listOf("/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md")
        )
        jniLibs {
            useLegacyPackaging = true
        }
    }

    flavorDimensions += "pikafish"
    productFlavors {
        create("armv8-") {
            //flavor configurations here
            dimension = "pikafish"
            buildConfigField("String", "PIKAFISH_ENGINE_FILE", "\"libpikafish-armv8.so\"")
        }
        create("armv8-dotprod-") {
            //flavor configurations here
            dimension = "pikafish"
            buildConfigField("String", "PIKAFISH_ENGINE_FILE", "\"libpikafish-armv8-dotprod.so\"")
        }
    }

    // Release APKs can use the local private key when explicitly supplied. Public source builds
    // fall back to the generated Android debug key instead of requiring a secret file.
    val releaseStoreFile = file(providers.gradleProperty("releaseStoreFile").orNull ?: "cchess.release.jks")
    val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
        ?: System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
        ?: System.getenv("ANDROID_RELEASE_KEY_ALIAS")
        ?: "cchess"
    val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
        ?: System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
    val hasPrivateReleaseKey = releaseStoreFile.isFile &&
        !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (hasPrivateReleaseKey) {
                storeFile = releaseStoreFile
                keyAlias = releaseKeyAlias
                storePassword = releaseStorePassword
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasPrivateReleaseKey) {
                signingConfigs["release"]
            } else {
                // A public checkout remains buildable without publishing the private release key.
                signingConfigs["debug"]
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }
    // https://gist.github.com/pankajXdev/574063901ada2fafa329068f41ddb076
    // Config your output file name in Gradle Kotlin DSL
    applicationVariants.all {
        outputs.all { output ->
            if (output is BaseVariantOutputImpl) {
                val date = SimpleDateFormat("yyyyMMdd").format(Date())
                val filename = "XiangqiAssist_${date}_${versionCode}_${name}.apk"
                output.outputFileName = filename
            }
            true
        }
    }
}

// https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
tasks.register<Jar>("nativeLibsToJar") {
    description = "create a jar archive of the native libs"
    destinationDirectory.set(layout.buildDirectory.dir("native-libs"))
    archiveBaseName.set("native-libs")
    from(fileTree("src/main/pikafish/") {
        include("**/*")
    })
    into("lib/")
}

tasks.named("preBuild") {
    dependsOn(tasks.named("nativeLibsToJar"))
}

dependencies {
    // https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
    implementation(files("$buildDir/native-libs/native-libs.jar"))
    // YOLOv5 棋子检测推理（优先使用 medium FP32 模型，设备内存不足时回退 n FP16 模型）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.readystatesoftware.sqliteasset:sqliteassethelper:+")
    // https://mvnrepository.com/artifact/com.igormaznitsa/jbbp
    implementation("com.igormaznitsa:jbbp:3.0.0")
    // https://github.com/PhilJay/MPAndroidChart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation(project(":filepicker"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.junit.jupiter)
}
