plugins { application }
dependencies {
    implementation(project(":j2r-driver"))
    implementation(libs.picocli)
}
application {
    mainClass.set("io.github.ykwyuta.j2r.cli.Main")
    applicationName = "j2r"
}
