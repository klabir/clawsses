pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "SherpaOnnxOfficialReleases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter {
                includeModule("com.k2fsa.sherpa", "sherpa-onnx-static-link-onnxruntime")
            }
        }
        // Rokid Maven repository for CXR SDKs
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}

rootProject.name = "Clawsses"

include(":phone-app")
include(":glasses-app")
include(":shared")
include(":benchmark")
