plugins {
    `java-library`
    signing
    id("application")
    id("me.champeau.jmh") version "0.7.2"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.github.flameyossnowy"
version = "1.2.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    //withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    compileOnly("org.jetbrains:annotations:26.0.2")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

jmh {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
    warmupIterations.set(5)
    iterations.set(10)
    fork.set(2)
    timeUnit.set("ns")
    benchmarkMode.set(listOf("thrpt", "avgt"))
    resultFormat.set("JSON")
}

configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    coordinates(group as String, "velocis", version as String)

    pom {
        name.set("Velocis")
        description.set("Velocis is a high-performance and lightweight data structure library for efficient caching and optimized data manipulation.")
        inceptionYear.set("2026")
        url.set("https://github.com/FlameyosSnowy/Velocis")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://mit-license.org/")
            }
        }
        developers {
            developer {
                id.set("flameyosflow")
                name.set("FlameyosFlow")
                url.set("https://github.com/FlameyosSnowy/")
            }
        }
        scm {
            url.set("https://github.com/FlameyosSnowy/Velocis")
            connection.set("scm:git:git://github.com/FlameyosSnowy/Velocis.git")
            developerConnection.set("scm:git:ssh://git@github.com/FlameyosSnowy/Velocis.git")
        }
    }

    publishToMavenCentral()
    signAllPublications()
}


signing {
    useGpgCmd()
}

afterEvaluate {
    tasks.named<com.vanniktech.maven.publish.tasks.JavadocJar>("plainJavadocJar") {
        dependsOn(tasks.named("javadoc"))
        archiveClassifier.set("javadoc")
        from(tasks.named<Javadoc>("javadoc"))
    }

    // Ensure metadata generation depends on Javadoc
    tasks.named("generateMetadataFileForMavenPublication") {
        dependsOn(tasks.named("plainJavadocJar"))
    }

    // Make publish depend on the Javadoc artifact
    tasks.named("publish") {
        dependsOn(tasks.named("plainJavadocJar"))
    }
}