import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    `java-library`
}

group = "com.crypto-chief"
version = "0.1.0"

description = "Kotlin SDK for the Crypto Chief crypto-processing API."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
        )
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.dokkaHtml.configure {
    outputDirectory.set(layout.buildDirectory.dir("dokka"))
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        skipEmptyPackages.set(true)
        jdkVersion.set(17)
        externalDocumentationLink {
            url.set(uri("https://kotlinlang.org/api/kotlinx.coroutines/").toURL())
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka("dokkaHtml"),
            sourcesJar = true,
        ),
    )

    coordinates(group.toString(), "cryptochief-crypto-processing-kotlin", version.toString())

    pom {
        name.set("Crypto Chief Processing SDK for Kotlin")
        description.set(project.description)
        url.set("https://github.com/crypto-chiefs/cryptochief-crypto-processing-kotlin")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("crypto-chiefs")
                name.set("Crypto Chief")
                email.set("dev@crypto-chief.com")
                organization.set("Crypto Chief")
                organizationUrl.set("https://crypto-chief.com")
            }
        }
        scm {
            url.set("https://github.com/crypto-chiefs/cryptochief-crypto-processing-kotlin")
            connection.set("scm:git:git://github.com/crypto-chiefs/cryptochief-crypto-processing-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/crypto-chiefs/cryptochief-crypto-processing-kotlin.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/crypto-chiefs/cryptochief-crypto-processing-kotlin/issues")
        }
    }
}
