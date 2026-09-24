// 移行元のデモアプリ: Spring Boot 4.1 + Spring MVC + MyBatis + Thymeleaf + PostgreSQL。
// 対応する Rust 版（../rust）は j2r が生成する（./gradlew translateToRust）。
// 対応関係は docs/07-spring-to-rust.md を参照。
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.github.ykwyuta.j2r")
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ./gradlew translateToRust で、アプリ全体（コントローラ・テンプレート・サービス・Mapper・設定）を ../rust に変換する。
j2r {
    framework.set("spring")
    crateName.set("todo")
    outputDir.set(layout.projectDirectory.dir("../rust"))
}
