buildscript {
    dependencies {
        // AGP 9 没有"内置 Kotlin 版本"这个开关；它驱动的是 classpath 上的 KGP
        // （KgpUtils 反射读 KGP 实例的版本）。AGP 自己声明 2.2.10，我们按官方
        // 发布说明的 "Upgrade to a higher KGP version" 显式声明 2.4.20 ——
        // 与 dimina 1.7.6 的元数据版本对齐，编译器才读得懂它的产物。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
