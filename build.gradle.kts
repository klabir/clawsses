import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

// Top-level build file
plugins {
    jacoco
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("com.android.test") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

val coverageProjects = setOf("phone-app", "glasses-app", "shared")

subprojects {
    if (name in coverageProjects) {
        pluginManager.apply("jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = "0.8.12"
        }
        tasks.withType<Test>().configureEach {
            extensions.configure<JacocoTaskExtension> {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

val releaseCoverageClassDirectories = files(
    project(":phone-app").fileTree("build/tmp/kotlin-classes/release") {
        include(
            "**/openclaw/BoundedChatStore*",
            "**/openclaw/StreamUpdateBuffer*",
            "**/openclaw/OpenClawRequestCoordinator*",
            "**/openclaw/OpenClawActiveSessionRuntime*",
            "**/glasses/*Gate*",
            "**/glasses/*Policy*",
            "**/service/WakeLockLeaseRegistry*",
        )
    },
    project(":glasses-app").fileTree("build/tmp/kotlin-classes/release") {
        include(
            "**/orchestration/HudActivityOrchestrator*",
            "**/orchestration/HudCatalogInteractionController*",
            "**/orchestration/HudCommandDispatcher*",
            "**/orchestration/HudInteractionPlanner*",
            "**/orchestration/HudPhoneMessageEffectPlanner*",
            "**/state/HudStateReducer*",
            "**/ui/HudPaginationCache*",
            "**/ui/HudStreamingAccumulator*",
        )
    },
    project(":shared").fileTree("build/tmp/kotlin-classes/release") {
        include("com/clawsses/shared/**")
    },
)

val releaseCoverageSourceDirectories = files(
    project(":phone-app").file("src/main/java"),
    project(":glasses-app").file("src/main/java"),
    project(":shared").file("src/main/java"),
)

val releaseCoverageExecutionData = files(
    project(":phone-app").file(
        "build/outputs/unit_test_code_coverage/releaseUnitTest/testReleaseUnitTest.exec"
    ),
    project(":glasses-app").file(
        "build/outputs/unit_test_code_coverage/releaseUnitTest/testReleaseUnitTest.exec"
    ),
    project(":shared").file(
        "build/outputs/unit_test_code_coverage/releaseUnitTest/testReleaseUnitTest.exec"
    ),
)

val releaseCoverageTests = listOf(
    ":phone-app:testReleaseUnitTest",
    ":glasses-app:testReleaseUnitTest",
    ":shared:testReleaseUnitTest",
)

tasks.register<JacocoReport>("releaseUnitTestCoverageReport") {
    group = "verification"
    description = "Reports release-unit-test coverage for deterministic Phone, HUD, and shared logic."
    dependsOn(releaseCoverageTests)
    classDirectories.setFrom(releaseCoverageClassDirectories)
    sourceDirectories.setFrom(releaseCoverageSourceDirectories)
    executionData.setFrom(releaseCoverageExecutionData)
    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/releaseUnitTest/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/releaseUnitTest/report.xml"))
        csv.required.set(false)
    }
}

tasks.register<JacocoCoverageVerification>("verifyReleaseUnitTestCoverage") {
    group = "verification"
    description = "Requires at least 70% line coverage for deterministic release logic."
    dependsOn(releaseCoverageTests)
    classDirectories.setFrom(releaseCoverageClassDirectories)
    sourceDirectories.setFrom(releaseCoverageSourceDirectories)
    executionData.setFrom(releaseCoverageExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}
