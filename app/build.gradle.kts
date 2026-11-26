/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import java.util.zip.ZipFile


plugins { alias(libs.plugins.android.application) }

private fun readU32(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xff) or
        ((data[offset + 1].toLong() and 0xff) shl 8) or
        ((data[offset + 2].toLong() and 0xff) shl 16) or
        ((data[offset + 3].toLong() and 0xff) shl 24)

private fun readUleb128(data: ByteArray, start: Int): Int {
    var result = 0
    var shift = 0
    var offset = start
    while (true) {
        check(offset < data.size) { "Malformed DEX string data" }
        val value = data[offset++].toInt() and 0xff
        result = result or ((value and 0x7f) shl shift)
        if ((value and 0x80) == 0) return offset
        shift += 7
        check(shift <= 28) { "Malformed DEX ULEB128" }
    }
}

private fun dexContainsString(data: ByteArray, target: String): Boolean {
    check(data.size >= 112 && data.copyOfRange(0, 3).contentEquals(byteArrayOf(0x64, 0x65, 0x78))) {
        "Extracted file is not a valid DEX header"
    }
    val stringIdsSize = readU32(data, 56).toInt()
    val stringIdsOffset = readU32(data, 60).toInt()
    check(stringIdsOffset >= 0 && stringIdsOffset.toLong() + stringIdsSize.toLong() * 4 <= data.size) {
        "Invalid DEX string table bounds"
    }
    val targetBytes = target.toByteArray(Charsets.UTF_8)
    for (index in 0 until stringIdsSize) {
        val stringOffset = readU32(data, stringIdsOffset + index * 4).toInt()
        check(stringOffset in data.indices) { "Invalid DEX string offset" }
        val dataOffset = readUleb128(data, stringOffset)
        var end = dataOffset
        while (end < data.size && data[end] != 0.toByte()) end++
        if (end < data.size && end - dataOffset == targetBytes.size &&
            data.copyOfRange(dataOffset, end).contentEquals(targetBytes)
        ) return true
    }
    return false
}



val gitCommitCount =
    providers
        .exec {
            commandLine("git", "rev-list", "HEAD", "--count")
            workingDir = rootDir
        }
        .standardOutput
        .asText
        .map { it.trim().toInt() }
        .get()

val gitCommitHash =
    providers
        .exec {
            commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
            workingDir = rootDir
        }
        .standardOutput
        .asText
        .map { it.trim() }
        .get()

val verName = "v3.1.0"

android {
    namespace = "io.github.beakthoven.TrickyStoreOSS"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.beakthoven.TrickyStoreOSS"
        minSdk = 29
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = verName

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=ON"
                arguments += "-DCMAKE_CXX_STANDARD=23"
                arguments += "-DCMAKE_C_STANDARD=23"
                arguments += "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON"

                cppFlags += "-std=c++23"
                cppFlags += "-fno-exceptions"
                cppFlags += "-fno-rtti"
                cppFlags += "-fvisibility=hidden"
                cppFlags += "-fvisibility-inlines-hidden"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.0+"
        }
    }
    buildFeatures { prefab = true }
    packaging { resources { pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/INDEX.LIST") } }
}

dependencies {
    compileOnly(project(":stub"))
    compileOnly(libs.annotation)
    implementation(libs.org.bouncycastle.bcpkix.jdk18on)
    implementation(libs.org.lsposed.libcxx.libcxx)
}

androidComponents {
    onVariants { variant: ApplicationVariant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val sourceDir = project.rootDir.resolve("module")
        val tempModuleDir = project.layout.buildDirectory.dir("tmp/module-${variantName}")
        val apkFile = variant.artifacts.get(SingleArtifact.APK)

        tasks.register("prepareModuleFiles${capitalized}") {
            notCompatibleWithConfigurationCache("stages APK contents into the variant module directory")
            inputs.dir(sourceDir)
            inputs.dir(apkFile)
            outputs.dir(tempModuleDir)

            doLast {
                val tempDir = tempModuleDir.get().asFile
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                sourceDir.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            file.name !in setOf("module.prop", "attestation.apk", "attestation.dex") &&
                            !file.relativeTo(sourceDir).invariantSeparatorsPath.startsWith("lib/")
                    }
                    .forEach { file ->
                        val destination = tempDir.resolve(file.relativeTo(sourceDir))
                        destination.parentFile.mkdirs()
                        file.copyTo(destination, overwrite = true)
                    }

                tempDir.resolve("module.prop").writeText(
                    sourceDir.resolve("module.prop")
                        .readText()
                        .replace("REPLACEMEVERCODE", gitCommitCount.toString())
                        .replace("REPLACEMEVER", "$verName ($gitCommitCount-$gitCommitHash-$variantName)"),
                )

                val apks = apkFile.get().asFile.walkTopDown()
                    .filter { it.isFile && it.extension == "apk" }
                    .sortedBy { it.invariantSeparatorsPath }
                    .toList()
                check(apks.size == 1) { "Expected exactly one APK output for $variantName, found ${apks.size}" }
                val apk = apks.single()

                ZipFile(apk).use { zip ->
                    val dexEntry = zip.getEntry("classes.dex")
                        ?: error("Generated APK does not contain classes.dex: ${apk.absolutePath}")

                    val dexDestination = tempDir.resolve("attestation.dex")
                    zip.getInputStream(dexEntry).use { input ->
                        dexDestination.outputStream().use(input::copyTo)
                    }
                    check(dexDestination.length() > 0L) {
                        "Extracted attestation.dex is empty for $variantName"
                    }

                    val dexBytes = dexDestination.readBytes()
                    val entryPoint = "Lio/github/beakthoven/TrickyStoreOSS/MainKt;"
                    check(dexContainsString(dexBytes, entryPoint)) {
                        "Required entry point $entryPoint is missing from classes.dex for $variantName. " +
                            "APK: ${apk.absolutePath}. The module ZIP will not be produced."
                    }

                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory && entry.name.startsWith("lib/") &&
                            entry.name.substringAfterLast('/') in setOf("libinject.so", "libTrickyStoreOSS.so")
                        ) {
                            val destination = tempDir.resolve(entry.name)
                            destination.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                destination.outputStream().use(input::copyTo)
                            }
                        }
                    }
                }
            }
        }

        tasks.register<Zip>("zip${capitalized}") {
            dependsOn("prepareModuleFiles${capitalized}")
            archiveFileName.set("Tricky-Store-OSS-$verName-$gitCommitCount-$gitCommitHash-${capitalized}.zip")
            destinationDirectory.set(project.rootDir.resolve("out"))
            from(tempModuleDir)
        }

        tasks.matching { it.name == "assemble${capitalized}" }.configureEach { finalizedBy("zip${capitalized}") }
    }
}
