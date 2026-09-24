// このリポジトリ内のトランスパイラ（Gradle プラグインと j2r-cli）をソースから使う設定。
// 公開後は pluginManagement / includeBuild を消し、プラグインのバージョンを指定するだけでよい。
pluginManagement {
    includeBuild("../..")
}
includeBuild("../..")

rootProject.name = "hello-gradle"
