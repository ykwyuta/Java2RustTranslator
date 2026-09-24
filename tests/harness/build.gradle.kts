plugins { java }

// 横断テスト: tests/e2e（JVM と cargo run の出力比較）と tests/golden（生成コードのスナップショット）。
dependencies {
    testImplementation(project(":j2r-driver"))
    testImplementation(project(":j2r-backend"))
}

tasks.test {
    val repoRoot = rootDir
    systemProperty("j2r.repoRoot", repoRoot.absolutePath)
    systemProperty("j2r.updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
    inputs.dir(repoRoot.resolve("tests/e2e")).withPropertyName("e2eCases")
    inputs.dir(repoRoot.resolve("tests/golden")).withPropertyName("goldenCases")
    inputs.dir(repoRoot.resolve("runtime/jrt/src")).withPropertyName("jrtSources")
}
