plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.5.11"
}

group = "com.example"
version = "1.9.4"

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
    archiveVersion.set("1.9.4")
}

tasks.assemble {
    dependsOn(tasks.reobfJar)
}

/**
 * Test-only authlib fixture. Never part of the production reobfuscated jar.
 * It lets CI test a complete RSA/AES/PlayerJoin path with a mocked session
 * provider, without requiring Microsoft credentials or a production bypass.
 */
tasks.register<Jar>("integrationFixtureJar") {
    dependsOn(tasks.testClasses)
    archiveFileName.set("SLogin-CI-Fixture.jar")
    from(sourceSets.test.get().output) {
        include("ci/fixture/SessionStubPlugin*.class")
    }
    from("src/test/fixture") {
        include("plugin.yml")
    }
}
