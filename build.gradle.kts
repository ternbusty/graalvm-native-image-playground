import org.gradle.api.tasks.Exec

plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

group = "playground"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Needed to compile against org.graalvm.nativeimage.hosted.{Feature,RuntimeForeignAccess}.
    // compileOnly because the GraalVM builder already provides these at native-image time.
    compileOnly("org.graalvm.sdk:nativeimage:25.0.2")
}

application {
    mainClass = "playground.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
    options.release = 25
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--enable-preview")
}

val sampleDir = layout.projectDirectory.dir("src/main/c/sample")
val sampleBuildDir = layout.buildDirectory.dir("sample")

val buildSample by tasks.registering(Exec::class) {
    val outDir = sampleBuildDir.get().asFile
    doFirst { outDir.mkdirs() }
    workingDir = sampleDir.asFile
    inputs.dir(sampleDir)
    outputs.dir(sampleBuildDir)
    // Build as a shared library so we can dlopen / Linker.libraryLookup it
    // at runtime. Statically linking a .a into the native-image binary works
    // for symbols that something else references, but a symbol only called
    // through FFM defaultLookup() gets stripped by native-image regardless of
    // __attribute__((used, visibility("default"))) and -Wl,--whole-archive.
    commandLine(
        "sh", "-c",
        "gcc -shared -fPIC -Wall -Wextra -O2 sample.c -o ${outDir.absolutePath}/libsample.so",
    )
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "playground"
            mainClass = "playground.Main"
            buildArgs.addAll(
                "--no-fallback",
                "-O3",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+ForeignAPISupport",
                "--enable-native-access=ALL-UNNAMED",
                "--enable-preview",
                "--features=playground.nativeimage.ForeignFeature",
            )
        }
    }
}

tasks.named("nativeCompile") {
    dependsOn(buildSample)
}
