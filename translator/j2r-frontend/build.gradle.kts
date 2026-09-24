plugins { `java-library` }
// javac（jdk.compiler）に依存してよいのはこのモジュールだけ。
dependencies { api(project(":j2r-jir")) }
