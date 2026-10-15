plugins {
    id("com.android.application")
}

android {
    namespace = "lobos"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    val verJson = groovy.json.JsonSlurper().parse(rootProject.file("version.json")) as Map<*, *>
    val shellVer = verJson["shell"] as Map<*, *>
    val appVersionName = shellVer["versionName"] as String
    val appVersionCode = (shellVer["versionCode"] as Number).toInt()
    val appBridgeProtocol = (shellVer["bridgeProtocol"] as Number).toInt()

    androidResources {
        noCompress += listOf("gz", "tar", "tar.gz")
    }

    defaultConfig {
        val resolvedAppId = System.getenv("LOBOS_APP_ID") ?: "lobos.os"
        require(resolvedAppId != "lobos.app") {
            "applicationId 不得为 lobos.app —— 那是设备上已装的旧包，同名会被当作覆盖安装，" +
                "而它正稳定运行着、我们不能破坏它。本项目自己的包名是 lobos.os。"
        }
        applicationId = resolvedAppId
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("int", "BRIDGE_PROTOCOL", appBridgeProtocol.toString())
        // 构建身份：装机后设备必须能自报「我是哪次构建」，否则每一轮真机读数都不可归因。
        // 占位值必须是 unknown 而不是任何形似 sha 的串 —— 否则 CI 注入失败会被占位掩盖。
        buildConfigField("String", "GIT_SHA", quoted(System.getenv("GITHUB_SHA") ?: "unknown"))
        buildConfigField("String", "RUN_ID", quoted(System.getenv("GITHUB_RUN_ID") ?: "unknown"))
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val keystorePath = System.getenv("LOBOS_KEYSTORE_PATH") ?: "keys/release.keystore"
    val releaseKeystore = rootProject.file(keystorePath)
    val hasReleaseKeystore = releaseKeystore.exists()

    val keystorePw = System.getenv("LOBOS_KEYSTORE_PASSWORD") ?: ""
    val keyPw = System.getenv("LOBOS_KEY_PASSWORD") ?: keystorePw
    val keyAliasName = System.getenv("LOBOS_KEY_ALIAS") ?: "lobos"

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystorePw
                keyAlias = keyAliasName
                keyPassword = keyPw
                require(keystorePw.isNotEmpty()) {
                    "检测到 keys/release.keystore，但 LOBOS_KEYSTORE_PASSWORD 为空。" +
                        "请设置该环境变量（CI: 由 secret 注入）。"
                }
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    if (!hasReleaseKeystore) {
        logger.warn(
            "[lobos-signing] ⚠ 未找到 ${releaseKeystore.path} —— 本次产物将使用 AGP 自动生成的 " +
                "debug 签名。后果：签名指纹每次都不同，新包无法覆盖安装到旧包上" +
                "（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。若这是发布构建，请配置密钥。" +
                "本地可用 ./scripts/publish/keygen-android-keystore.sh 生成。"
        )
    } else {
        logger.lifecycle("[lobos-signing] 使用稳定签名: ${releaseKeystore.path}")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            jniLibs {
                useLegacyPackaging = true

                keepDebugSymbols += nativeAssetNames().map { "**/$it" }
            }
        }
    }
}

// buildConfigField("String", …) 的实参必须是 Java 源码字面量，故自带引号。
// 不加引号会生成裸词 → 编译期报「expecting an element」。
fun quoted(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun nativeAssetNames(): List<String> {
    val f = rootProject.file(".github/native-assets.txt")
    if (!f.exists()) {
        throw GradleException(
            "缺少 .github/native-assets.txt —— 它是 NativeAssetRegistry 的投影，构建必需。\n" +
                "该文件同时被 CI（build-apk.yml）读取，用于下载校验与 APK 审计。"
        )
    }
    val names = f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
    if (names.isEmpty()) {
        throw GradleException(".github/native-assets.txt 里没有任何资产名 —— 是不是被清空了？")
    }
    return names
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 件包是 tar.gz —— Android 只有 zip 的公开 API（java.util.zip.ZipFile），
    // 没有 tar。commons-compress 是解 tar 的标准选择。
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.github.didi.dimina:dimina:1.7.6")

    testImplementation("junit:junit:4.13.2")
}
