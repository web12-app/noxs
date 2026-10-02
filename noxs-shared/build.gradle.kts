plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // XZ for Java — public domain, used only for .tar.xz rootfs decompression.
    implementation("org.tukaani:xz:1.9")
    testImplementation("junit:junit:4.13.2")
}
