plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.5.11"
}

group = "com.example"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.20.1-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("SLogin")
    archiveVersion.set("1.3.0")
}

tasks.assemble {
    dependsOn(tasks.reobfJar)
}
