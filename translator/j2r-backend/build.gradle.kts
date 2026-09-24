plugins { `java-library` }

dependencies {
    api(project(":j2r-rir"))
    api(project(":j2r-common"))
}

// ランタイム crate jrt のソースをリソースとして同梱し、出力先へコピー（vendor）できるようにする。
val jrtRoot = rootProject.file("runtime/jrt")
val bundleJrt by tasks.registering {
    description = "Bundles the jrt runtime crate sources as resources."
    val outDir = layout.buildDirectory.dir("generated/jrt-resources")
    inputs.dir(jrtRoot.resolve("src"))
    inputs.file(jrtRoot.resolve("Cargo.toml"))
    outputs.dir(outDir)
    val root = jrtRoot
    doLast {
        val base = outDir.get().asFile.resolve("j2r/runtime/jrt")
        base.deleteRecursively()
        base.mkdirs()
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it == "Cargo.toml" || it.startsWith("src/") }
            .sorted()
            .toList()
        files.forEach { rel -> root.resolve(rel).copyTo(base.resolve(rel), overwrite = true) }
        base.resolve("index.txt").writeText(files.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main {
    resources.srcDir(bundleJrt)
}
