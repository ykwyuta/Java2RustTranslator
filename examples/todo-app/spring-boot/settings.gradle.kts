// このリポジトリ内のトランスパイラ（Gradle プラグインと j2r-cli）をソースから使う設定（examples/hello-gradle と同じ）。
pluginManagement {
    includeBuild("../../..")
}
includeBuild("../../..")

rootProject.name = "todo-spring-boot"
