plugins {
    id("java")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "grammar_extractor.src.main.java.grammarextractor.Main"
    }
}


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