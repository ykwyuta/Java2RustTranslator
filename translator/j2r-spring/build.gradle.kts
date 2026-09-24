plugins { `java-library` }

// Spring Boot + MyBatis のアプリを axum / sqlx の構成に変換する規則（--framework spring）。
// JIR（型付きの Java の構文木）を読み、RIR（Rust の構文木）を組み立てる。javac には依存しない。
dependencies {
    api(project(":j2r-jir"))
    api(project(":j2r-rir"))
    api(project(":j2r-common"))
}
