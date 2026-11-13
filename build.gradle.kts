plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "com.example"
version = "2.0.0-26.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.3.build.+")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("SLogin")
    archiveVersion.set("2.0.0-26.3")
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
