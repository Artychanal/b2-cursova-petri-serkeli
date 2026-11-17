import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Версії та контрольні суми. */
public final class RunProvenance {

    private RunProvenance() {
    }

    public static Map<String, Object> collect() throws Exception {
        var m = new LinkedHashMap<String, Object>();
        m.put("modelRevision", "capacity-S-v2");
        m.put("libraryCommit", "34116fb0f4c332851ca83f75a277ee223a286ef6");
        m.put("librarySha256", sha256(Path.of("lib/PetriObjModelPaint-34116fb.jar")));
        m.put("javaVersion", System.getProperty("java.version"));
        var sources = new LinkedHashMap<String, String>();
        try (var files = Files.list(Path.of("src"))) {
            for (Path p:files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.put(p.getFileName().toString(), sha256(p));
            }
        }
        m.put("sourceSha256", sources);
        return m;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
