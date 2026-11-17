import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Запис результатів JSON. */
public final class Json {

    private Json() {
    }

    public static void write(Path target, Object value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, encode(value)+"\n", StandardCharsets.UTF_8);
    }

    public static String encode(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return quote(s);
        }
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                throw new IllegalArgumentException("Nonfinite JSON number");
            }
            return n.toString();
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var parts = new java.util.ArrayList<String>();
            for (var e : map.entrySet()) {
                parts.add(quote(e.getKey().toString())+":"+encode(e.getValue()));
            }
            return "{"+String.join(",", parts)+"}";
        }
        if (value instanceof Iterable<?> sequence) {
            var parts = new java.util.ArrayList<String>();
            for (Object item : sequence) {
                parts.add(encode(item));
            }
            return "["+String.join(",", parts)+"]";
        }
        if (value.getClass().isArray()) {
            var parts = new java.util.ArrayList<String>();
            for (int j = 0; j < Array.getLength(value); j++) {
                parts.add(encode(Array.get(value, j)));
            }
            return "["+String.join(",", parts)+"]";
        }
        throw new IllegalArgumentException("Unsupported JSON type: "+value.getClass());
    }

    private static String quote(String s) {
        var b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 32) {
                        b.append(String.format("\\u%04x", (int)c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
