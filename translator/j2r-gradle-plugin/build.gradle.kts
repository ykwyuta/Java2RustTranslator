plugins { `java-gradle-plugin` }

// Gradle プラグイン: 利用側の Java プロジェクトに translateToRust / cargoCheck / cargoBuild / cargoRun タスクを追加する。
// 変換器本体（j2r-cli）は別 JVM で実行するため、ここでは依存しない（j2rTranslator 構成で解決する）。
gradlePlugin {
    plugins {
        create("j2r") {
            id = "io.github.ykwyuta.j2r"
            implementationClass = "io.github.ykwyuta.j2r.gradle.J2rPlugin"
            displayName = "Java2RustTranslator"
            description = "Translates the main Java source set into a Rust Cargo project."
        }
    }
}

// プラグインが既定で使う j2r-cli のバージョンを埋め込む。
val generatePluginProperties by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/plugin-properties")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().asFile.resolve("io/github/ykwyuta/j2r/gradle/j2r-plugin.properties")
        f.parentFile.mkdirs()
        f.writeText("version=$ver\n")
    }
}
sourceSets.main {
    resources.srcDir(generatePluginProperties)
}
