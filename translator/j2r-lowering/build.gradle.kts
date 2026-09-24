plugins { `java-library` }
dependencies {
    api(project(":j2r-jir"))
    api(project(":j2r-rir"))
    implementation(project(":j2r-analysis"))
    implementation(project(":j2r-mappings"))
    implementation(libs.snakeyaml.engine)
}
