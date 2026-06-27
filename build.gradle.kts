import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("com.github.recloudstream:gradle:1.0.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        project.extensions.findByType(JavaPluginExtension::class.java)?.apply {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }

        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/trinityzanetamanu-code/Premium_RepoX")
        authors = listOf("trinityzanetamanu")
    }

    android {
        namespace = "com.trinityzanetamanu"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
            targetSdk = 36
        }

        lint {
            warningsAsErrors = false
            abortOnError = false
            checkReleaseBuilds = false
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        //noinspection WrongGradleMethod
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-opt-in=kotlin.RequiresOptIn",
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // ===== Core Kotlin =====
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

        // Nullability annotations for Kotlin 2.4.0 strict checking
        implementation("org.jspecify:jspecify:1.0.0")

        // ===== HTTP & Network =====
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
        implementation("com.squareup.okhttp3:okhttp")

        // ===== JSON Parsing =====
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

        // ===== Coroutines =====
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

        // ===== JavaScript Engine =====
        implementation("org.mozilla:rhino:1.8.1")

        // ===== String Matching =====
        implementation("me.xdrop:fuzzywuzzy:1.4.0")

        // ===== Crypto (untuk Adimoviebox HMAC signing) =====
        implementation("org.bouncycastle:bcpkix-jdk18on:1.84")

        // ===== AndroidX =====
        implementation("androidx.annotation:annotation:1.10.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
