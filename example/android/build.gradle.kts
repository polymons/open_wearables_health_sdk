allprojects {
    repositories {
        google()
        mavenCentral()
        // LOCAL DEV: Samsung Health SDK bundled in local Android SDK
        maven {
            url = uri("${rootProject.projectDir}/../../../open-wearables-android-sdk/sdk/libs/maven")
        }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
