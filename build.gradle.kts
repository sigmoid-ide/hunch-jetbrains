import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "com.github.sigmoidide.hunch"
version = "0.1.1"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Use the platform interfaces' own default methods instead of generating forwarding
        // methods, which the Plugin Verifier reports as uses of deprecated and experimental API.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Build against the oldest supported PyCharm; newer ones load it too.
        pycharm("2026.1.5")
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}
