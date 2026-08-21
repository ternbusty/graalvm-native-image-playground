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

    // JNA for the JNI/JNA/FFM comparison example
    implementation("net.java.dev.jna:jna:5.16.0")
}

application {
    mainClass = "playground.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
    options.release = 25
    // Generate JNI headers for classes with native methods
    options.headerOutputDirectory = layout.buildDirectory.dir("generated/jni-headers")
}

tasks.named<JavaExec>("run") {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
    )
    // JNI shared library lookup path
    systemProperty("java.library.path", layout.buildDirectory.dir("jni").get().asFile.absolutePath)
}

// ── C shared libraries ─────────────────────────────────────────────

// sample.so (existing FFM demo)
val sampleDir = layout.projectDirectory.dir("src/main/c/sample")
val sampleBuildDir = layout.buildDirectory.dir("sample")

val buildSample by tasks.registering(Exec::class) {
    val outDir = sampleBuildDir.get().asFile
    doFirst { outDir.mkdirs() }
    workingDir = sampleDir.asFile
    inputs.dir(sampleDir)
    outputs.dir(sampleBuildDir)
    commandLine(
        "sh", "-c",
        "gcc -shared -fPIC -Wall -Wextra -O2 sample.c -o ${outDir.absolutePath}/libsample.so",
    )
}

// jnisyscall.so (JNI comparison demo)
val jniDir = layout.projectDirectory.dir("src/main/c/jni")
val jniBuildDir = layout.buildDirectory.dir("jni")

val buildJni by tasks.registering(Exec::class) {
    dependsOn("compileJava")
    val outDir = jniBuildDir.get().asFile
    val headersDir = layout.buildDirectory.dir("generated/jni-headers").get().asFile
    doFirst { outDir.mkdirs() }
    workingDir = jniDir.asFile
    inputs.dir(jniDir)
    inputs.dir(headersDir)
    outputs.dir(jniBuildDir)
    commandLine(
        "sh", "-c",
        """gcc -shared -fPIC -Wall -Wextra -O2 \
            -I"${'$'}JAVA_HOME/include" \
            -I"${'$'}JAVA_HOME/include/linux" \
            -I"${headersDir.absolutePath}" \
            jni_syscall.c \
            -o "${outDir.absolutePath}/libjnisyscall.so"""",
    )
}

tasks.named("classes") {
    dependsOn(buildJni)
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
    dependsOn(buildSample, buildJni)
}
