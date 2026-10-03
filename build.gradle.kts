plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.August"
version = "2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    paperweight.foliaDevBundle("26.2.build.7-beta")

    implementation("com.github.luben:zstd-jni:1.5.5-11")
    implementation("net.openhft:zero-allocation-hashing:0.16")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    shadowJar {
        archiveClassifier.set("")
    }

    assemble {
        dependsOn(shadowJar)
    }
}