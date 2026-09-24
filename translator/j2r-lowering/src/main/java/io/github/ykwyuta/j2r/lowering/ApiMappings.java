package io.github.ykwyuta.j2r.lowering;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * JDK API → Rust のマッピング規則（YAML）。既定の規則は j2r-mappings の
 * {@code j2r/mappings/*.yaml}、ユーザ定義の規則は追加ディレクトリから読み込む（後から読んだものが優先）。
 */
public final class ApiMappings {
    private static final String RESOURCE_DIR = "j2r/mappings/";

    private final Map<String, String> rustTypes = new HashMap<>();
    private final Map<String, String> fields = new HashMap<>();
    private final Map<String, String> methods = new HashMap<>();
    private final Map<String, String> constructors = new HashMap<>();

    public static ApiMappings load(List<Path> extraDirs) {
        ApiMappings m = new ApiMappings();
        ClassLoader cl = ApiMappings.class.getClassLoader();
        for (String file : readIndex(cl)) {
            try (InputStream in = cl.getResourceAsStream(RESOURCE_DIR + file)) {
                if (in == null) {
                    throw new IllegalStateException("mapping resource not found: " + file);
                }
                m.addYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8), file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (Path dir : extraDirs) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList()) {
                    m.addYaml(Files.readString(f), f.toString());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return m;
    }

    private static List<String> readIndex(ClassLoader cl) {
        try (InputStream in = cl.getResourceAsStream(RESOURCE_DIR + "index.txt")) {
            if (in == null) {
                throw new IllegalStateException("j2r/mappings/index.txt not found on classpath (is j2r-mappings missing?)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    void addYaml(String yaml, String origin) {
        Object root = new Load(LoadSettings.builder().setLabel(origin).build()).loadFromString(yaml);
        if (!(root instanceof Map<?, ?> map) || !(map.get("classes") instanceof List<?> classes)) {
            throw new IllegalArgumentException(origin + ": expected a top-level 'classes' list");
        }
        for (Object o : classes) {
            Map<String, Object> c = (Map<String, Object>) o;
            Object clsValue = c.get("class");
            if (clsValue == null) {
                throw new IllegalArgumentException(origin + ": entry without 'class'");
            }
            // class には 1 つのクラス名か、同じ規則を共有するクラス名のリスト（List / ArrayList / ... など）を書ける。
            List<String> classNames = clsValue instanceof List<?> l ? (List<String>) l : List.of((String) clsValue);
            for (String cls : classNames) {
                addClass(cls, c);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addClass(String cls, Map<String, Object> c) {
        if (c.get("rustType") instanceof String rt) {
            rustTypes.put(cls, rt);
        }
        for (Map<String, Object> f : list(c.get("fields"))) {
            fields.put(cls + "#" + f.get("name"), (String) f.get("rust"));
        }
        for (Map<String, Object> f : list(c.get("methods"))) {
            methods.put(cls + "#" + normalize((String) f.get("sig")), (String) f.get("rust"));
        }
        for (Map<String, Object> f : list(c.get("constructors"))) {
            constructors.put(cls + "#" + normalize((String) f.get("sig")), (String) f.get("rust"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    private static String normalize(String sig) {
        return sig.replace(" ", "");
    }

    /** Java クラスに対応する Rust の型。なければ null。 */
    public String rustType(String javaClass) {
        return rustTypes.get(javaClass);
    }

    public String field(String javaClass, String name) {
        return fields.get(javaClass + "#" + name);
    }

    /** @param signature 例: {@code println(java.lang.String)} */
    public String method(String javaClass, String signature) {
        return methods.get(javaClass + "#" + signature);
    }

    public String constructor(String javaClass, String signature) {
        return constructors.get(javaClass + "#" + signature);
    }
}
