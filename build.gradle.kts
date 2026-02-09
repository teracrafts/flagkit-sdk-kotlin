plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    `maven-publish`
    signing
    jacoco
}

group = "com.teracrafts"
version = "1.0.6"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.ktor:ktor-client-mock:2.3.7")
    testImplementation("io.mockk:mockk:1.13.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// SDK Lab task for internal verification
sourceSets {
    create("lab") {
        kotlin.srcDir("sdk-lab")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["labImplementation"].extendsFrom(configurations.implementation.get())
configurations["labRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("lab") {
    description = "Run SDK lab verification"
    group = "verification"
    mainClass.set("sdklab.RunnerKt")
    classpath = sourceSets["lab"].runtimeClasspath
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(javadocJar)

            pom {
                name.set("FlagKit Kotlin SDK")
                description.set("Official Kotlin SDK for FlagKit feature flag management")
                url.set("https://github.com/teracrafts/flagkit-sdk")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        name.set("Teracrafts Team")
                        email.set("hello@huefy.dev")
                        organization.set("Teracrafts")
                        organizationUrl.set("https://github.com/teracrafts")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/teracrafts/flagkit-sdk.git")
                    developerConnection.set("scm:git:ssh://github.com:teracrafts/flagkit-sdk.git")
                    url.set("https://github.com/teracrafts/flagkit-sdk/tree/main")
                }
            }
        }
    }

    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    } else {
        useGpgCmd()
    }
    sign(publishing.publications["maven"])
}
