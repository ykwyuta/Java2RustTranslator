package io.github.ykwyuta.j2r.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 生成した Cargo プロジェクトに対して cargo を実行する。 */
public final class CargoRunner {
    private CargoRunner() {}

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** cargo が PATH にあるか。 */
    public static boolean available() {
        try {
            Process p = new ProcessBuilder("cargo", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code cargo check} を実行する。警告もエラーも出力に含まれる。 */
    public static Result check(Path projectDir) {
        return run(projectDir, List.of("check", "--quiet", "--message-format=short"), null, java.util.Map.of());
    }

    /**
     * @param stdin null でなければ標準入力に渡す
     * @param env   追加の環境変数（CARGO_TARGET_DIR など）
     */
    public static Result run(Path projectDir, List<String> cargoArgs, byte[] stdin, java.util.Map<String, String> env) {
        List<String> cmd = new ArrayList<>();
        cmd.add("cargo");
        cmd.addAll(cargoArgs);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(projectDir.toFile()).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process p = pb.start();
            if (stdin != null) {
                p.getOutputStream().write(stdin);
            }
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
