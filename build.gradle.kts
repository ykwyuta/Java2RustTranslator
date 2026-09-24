// 全 Java サブプロジェクト共通の設定。
allprojects {
    group = "io.github.ykwyuta.j2r"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
            options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
            "testImplementation"(libs.findLibrary("junit-jupiter").get())
            "testImplementation"(libs.findLibrary("assertj-core").get())
            "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
        }
    }
}
