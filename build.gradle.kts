plugins {
    id("java")
}

group = "com.feelsolazy"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    testCompileOnly("org.projectlombok:lombok:1.18.42")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.release = 25
    options.encoding = "UTF-8"
}

// Self-contained runnable jar: build/libs/outageguard.jar
tasks.jar {
    archiveFileName = "outageguard.jar"
    manifest { attributes["Main-Class"] = "com.feelsolazy.Main" }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Signatures and per-library module descriptors don't apply to the merged jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Launcher icons (icon.ico for Windows, icon.png for Linux) drawn by the same code as the tray icon.
val generateIcons = tasks.register<JavaExec>("generateIcons") {
    description = "Generates build/icons/icon.ico and icon.png from Icons.java"
    val iconDir = layout.buildDirectory.dir("icons")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.feelsolazy.Icons"
    args(iconDir.get().asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true")
    inputs.files(sourceSets.main.get().output)
    outputs.dir(iconDir)
}

// Native app image with a bundled, trimmed Java runtime (no Java needed on the target PC).
// Windows: build/dist/OutageGuard/OutageGuard.exe. jpackage only builds for the OS it runs on.
tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Builds build/dist/OutageGuard with a native launcher and bundled runtime via jpackage"
    dependsOn(tasks.jar, generateIcons)

    val windows = System.getProperty("os.name").startsWith("Windows")
    val icon = layout.buildDirectory.file(if (windows) "icons/icon.ico" else "icons/icon.png")
    val distDir = layout.buildDirectory.dir("dist")
    val libsDir = layout.buildDirectory.dir("libs")
    val jdkHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.metadata.installationPath.asFile }
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.dir(distDir)

    doFirst {
        // jpackage marks the launcher exe read-only, which blocks deletion on Windows.
        val dist = distDir.get().asFile
        dist.walk().forEach { it.setWritable(true) }
        if (!dist.deleteRecursively()) throw GradleException("Cannot delete $dist (is OutageGuard running?)")
        val exe = if (windows) "jpackage.exe" else "jpackage"
        executable = jdkHome.get().resolve("bin/$exe").absolutePath
    }
    args(
        "--type", "app-image",
        "--name", "OutageGuard",
        "--app-version", "1.0.0",
        "--vendor", "feelsolazy",
        "--icon", icon.get().asFile.absolutePath,
        "--input", libsDir.get().asFile.absolutePath,
        "--main-jar", "outageguard.jar",
        "--add-launcher", "outageguard-cli=" + file("src/jpackage/outageguard-cli.properties").absolutePath,
        // From `jdeps --print-module-deps build/libs/outageguard.jar` (java.naming/java.compiler: Logback,
        // java.sql: Gson), plus jdk.unsupported for Gson. java.xml (Logback config) comes with java.desktop.
        "--add-modules", "java.base,java.compiler,java.desktop,java.naming,java.net.http,java.sql,jdk.unsupported",
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        "--dest", distDir.get().asFile.absolutePath,
    )
}

tasks.test {
    useJUnitPlatform()
}
