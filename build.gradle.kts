plugins {
    id("java")
}

val timestamp = project.findProperty("TIMESTAMP") ?: "local"
version = "1.0-${timestamp}-SNAPSHOT"

group = "grammarextractor"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
