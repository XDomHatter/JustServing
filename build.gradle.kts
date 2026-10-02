plugins {
    java
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "tech.xdomhatter"
version = "1.0.0"

repositories {
    mavenCentral()
    // jediterm（GUI 内嵌终端）未发布到 Maven Central，JetBrains 官方仓库提供
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.formdev:flatlaf:3.6")
    implementation("com.formdev:flatlaf-extras:3.6")
    implementation("org.jetbrains.jediterm:jediterm-core:3.73")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.73")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "tech.xdomhatter.Main"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "tech.xdomhatter.Main"
    }
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "tech.xdomhatter.Main"
    }
}