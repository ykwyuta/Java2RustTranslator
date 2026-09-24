rootProject.name = "java2rust-translator"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// トランスパイラ本体のモジュール（translator/ 配下）。依存方向は docs/04-directory-structure.md を参照。
val translatorModules = listOf(
    "j2r-common",
    "j2r-jir",
    "j2r-frontend",
    "j2r-passes",
    "j2r-analysis",
    "j2r-rir",
    "j2r-mappings",
    "j2r-lowering",
    "j2r-backend",
    "j2r-driver",
    "j2r-cli",
    "j2r-gradle-plugin",
)
translatorModules.forEach { name ->
    include(name)
    project(":$name").projectDir = file("translator/$name")
}

include("j2r-test-harness")
project(":j2r-test-harness").projectDir = file("tests/harness")
