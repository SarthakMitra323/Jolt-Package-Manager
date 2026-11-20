// File: build.gradle.kts
plugins {
    application
    id("org.graalvm.buildtools.native") version "0.10.2"
}

group = "dev.jolt"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("dev.jolt.Main")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "--enable-preview"
    ))
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("jolt")
            mainClass.set("dev.jolt.Main")
            sharedLibrary.set(false)
            useFatJar.set(false)
            
            buildArgs.addAll(listOf(
                "-O3",                                  // Maximum Graal compiler optimization
                "--gc=serial",                          // Lowest footprint & startup overhead
                "-R:MinHeapSize=16m",                   // Pre-reserve young gen so CLI runs trigger 0 GC cycles
                "-R:MaxHeapSize=512m",
                "--enable-url-protocols=https,http",    // Required for PyPI TLS
                "--enable-native-access=ALL-UNNAMED",   // For future FFM reflinks/hardlinks
                "--no-fallback",                        // Fail build if standalone binary isn't possible
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RemoveUnusedSymbols",
                "-march=native"                         // Emit AVX2/NEON instructions for host CPU
            ))
        }
    }
}