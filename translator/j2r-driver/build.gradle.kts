plugins { `java-library` }
dependencies {
    api(project(":j2r-common"))
    implementation(project(":j2r-frontend"))
    implementation(project(":j2r-passes"))
    implementation(project(":j2r-analysis"))
    implementation(project(":j2r-lowering"))
    implementation(project(":j2r-spring"))
    implementation(project(":j2r-backend"))
}
