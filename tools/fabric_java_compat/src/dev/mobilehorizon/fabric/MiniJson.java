package dev.mobilehorizon.fabric;

import java.util.*;

/** Small JSON parser/serializer so the standalone host has no runtime library dependency. */
final class MiniJson {
    private MiniJson() {}
    static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.value(0);
        p.space();
        if (p.i != s.length()) throw new IllegalArgumentException("Trailing JSON content");
        return v;
    }
    static String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return quote((String)v);
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?>) {
            StringBuilder b = new StringBuilder("{"); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>)v).entrySet()) {
                if (!first) b.append(',');
                first = false; b.append(quote(String.valueOf(e.getKey()))).append(':').append(stringify(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (v instanceof Iterable<?>) {
            StringBuilder b = new StringBuilder("["); boolean first = true;
            for (Object e : (Iterable<?>)v) { if (!first) b.append(','); first = false; b.append(stringify(e)); }
            return b.append(']').toString();
        }
        return quote(String.valueOf(v));
    }
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        void space() { while (i < s.length() && s.charAt(i) <= ' ') i++; }
        Object value(int depth) {
            if (depth > 100) throw new IllegalArgumentException("JSON nesting limit exceeded");
            space(); if (i >= s.length()) throw new IllegalArgumentException("Unexpected JSON end");
            char c = s.charAt(i);
            if (c == '{') return object(depth + 1);
            if (c == '[') return array(depth + 1);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }
        Map<String,Object> object(int d) {
            i++; space(); Map<String,Object> m = new LinkedHashMap<>();
            if (take('}')) return m;
            while (true) {
                space(); if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("Expected object key");
                String k = string(); space(); if (!take(':')) throw new IllegalArgumentException("Expected ':'");
                if (m.containsKey(k)) throw new IllegalArgumentException("Duplicate object key");
                m.put(k, value(d)); space(); if (take('}')) return m;
                if (!take(',')) throw new IllegalArgumentException("Expected ','");
            }
        }
        List<Object> array(int d) {
            i++; space(); List<Object> a = new ArrayList<>(); if (take(']')) return a;
            while (true) {
                a.add(value(d)); if (a.size() > 100000) throw new IllegalArgumentException("JSON array limit exceeded");
                space(); if (take(']')) return a; if (!take(',')) throw new IllegalArgumentException("Expected ','");
            }
        }
        String string() {
            if (!take('"')) throw new IllegalArgumentException("Expected string");
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++); if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break; case '\\': b.append('\\'); break; case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break; case 'f': b.append('\f'); break; case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                            b.append((char)Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: throw new IllegalArgumentException("Bad JSON escape");
                    }
                } else { if (c < 0x20) throw new IllegalArgumentException("Control character"); b.append(c); }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }
        Number number() {
            int start = i; if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') { i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++; if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (start == i) throw new IllegalArgumentException("Invalid JSON value");
            String raw = s.substring(start, i);
            try { return raw.matches(".*[.eE].*") ? Double.valueOf(raw) : Long.valueOf(raw); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid JSON number"); }
        }
        boolean take(char c) { if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }
    }
}
