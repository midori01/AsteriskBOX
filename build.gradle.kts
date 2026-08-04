plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<UpdateResourceFileAssetsTask>("updateResourceFileAssets") {
    singBoxVersion.set(ProjectConfig.SING_BOX_VERSION)
    githubToken.set(project.findProperty("github.token") as? String ?: System.getenv("GITHUB_TOKEN") ?: "")
    singBoxCoreJniLibsDir.set(layout.projectDirectory.dir("app/build/generated/singBoxCoreJniLibs"))
    resourceFileAssetsDir.set(layout.projectDirectory.dir("app/build/generated/resourceFileAssets"))
}
