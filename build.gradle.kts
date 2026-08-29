import org.gradle.api.tasks.Exec

plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.10"
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
    compileOnly("org.graalvm.sdk:nativeimage:25.2.4")
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
                "-H:-DeleteLocalSymbols",
                "-H:+PreserveFramePointer",
                "--initialize-at-build-time=playground.BenchDemo,playground.CInteropGetpid,playground.CInteropGetpid\$Directives,playground.VariadicDemo,playground.VariadicDemo\$Directives",
                "--initialize-at-run-time=playground.FfmGetpid",
                "--features=" + listOf(
                    "playground.BasicDemo",
                    "playground.SyscallDemo",
                    "playground.FileIoDemo",
                    "playground.CallCDemo",
                    "playground.ForkDemo",
                    "playground.LeakDemo",
                    "playground.SafepointHangDemo",
                    "playground.UnshareDemo",
                    "playground.BenchDemo",
                ).joinToString(",") { "$it\$Registration" },
            )
        }
    }
}

tasks.named("nativeCompile") {
    dependsOn(buildSample)
}
