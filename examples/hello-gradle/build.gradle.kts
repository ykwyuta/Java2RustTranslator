plugins {
    java
    id("io.github.ykwyuta.j2r")
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

j2r {
    crateName.set("hello")
    mainClass.set("com.example.hello.App")
    // outputDir.set(layout.buildDirectory.dir("rust"))  // 既定値
}
