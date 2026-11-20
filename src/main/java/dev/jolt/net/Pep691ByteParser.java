// File: src/main/java/dev/jolt/net/Pep691ByteParser.java
package dev.jolt.net;

import dev.jolt.net.PyPIClient.ArtifactFile;
import dev.jolt.net.PyPIClient.PackageIndex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-pass byte-array JSON reader tailored strictly for PEP 691 Simple API responses.
 * Avoids generic JSON DOM allocation overhead.
 */
final class Pep691ByteParser {
    private final byte[] buf;
    private int pos;
    private final int len;

    private Pep691ByteParser(byte[] buf) {
        this.buf = buf;
        this.pos = 0;
        this.len = buf.length;
    }

    static PackageIndex parse(String pkgName, byte[] jsonBytes, String etag) {
        return new Pep691ByteParser(jsonBytes).parseRoot(pkgName, etag);
    }

    private PackageIndex parseRoot(String pkgName, String etag) {
        List<String> versions = new ArrayList<>(64);
        List<ArtifactFile> files = new ArrayList<>(256);

        skipWhitespace();
        expect((byte) '{');

        while (pos < len) {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                break;
            }
            String key = readString();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();

            switch (key) {
                case "versions" -> parseVersionsArray(versions);
                case "files" -> parseFilesArray(files);
                default -> skipValue();
            }
            skipWhitespace();
            if (peek() == ',') pos++;
        }

        return new PackageIndex(pkgName, versions, files, etag);
    }

    private void parseVersionsArray(List<String> versions) {
        expect((byte) '[');
        while (pos < len) {
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                break;
            }
            versions.add(readString());
            skipWhitespace();
            if (peek() == ',') pos++;
        }
    }

    private void parseFilesArray(List<ArtifactFile> files) {
        expect((byte) '[');
        while (pos < len) {
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                break;
            }
            ArtifactFile file = parseSingleFileObject();
            if (file != null) {
                files.add(file);
            }
            skipWhitespace();
            if (peek() == ',') pos++;
        }
    }

    private ArtifactFile parseSingleFileObject() {
        expect((byte) '{');
        String filename = null;
        String url = null;
        String sha256 = null;
        String requiresPython = null;
        boolean hasMetadata = false;
        boolean yanked = false;

        while (pos < len) {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                break;
            }
            String key = readString();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();

            switch (key) {
                case "filename" -> filename = readString();
                case "url" -> url = readString();
                case "requires-python" -> {
                    if (peek() == 'n') { pos += 4; } // null
                    else requiresPython = readString();
                }
                case "hashes" -> sha256 = parseHashesObject();
                case "data-dist-info-metadata", "core-metadata" -> {
                    byte b = peek();
                    if (b == 't') { hasMetadata = true; pos += 4; }
                    else if (b == '{') { hasMetadata = true; skipObject(); }
                    else skipValue();
                }
                case "yanked" -> {
                    byte b = peek();
                    if (b == 't') { yanked = true; pos += 4; }
                    else if (b == '"') { yanked = true; skipString(); } // yanked reason string
                    else skipValue();
                }
                default -> skipValue();
            }
            skipWhitespace();
            if (peek() == ',') pos++;
        }

        if (filename == null || url == null) return null;
        return new ArtifactFile(filename, url, sha256, requiresPython, hasMetadata, yanked);
    }

    private String parseHashesObject() {
        expect((byte) '{');
        String sha256 = null;
        while (pos < len) {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                break;
            }
            String algo = readString();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if ("sha256".equals(algo)) {
                sha256 = readString();
            } else {
                skipString();
            }
            skipWhitespace();
            if (peek() == ',') pos++;
        }
        return sha256;
    }

    private String readString() {
        expect((byte) '"');
        int start = pos;
        while (pos < len) {
            byte b = buf[pos];
            if (b == '"') {
                String s = new String(buf, start, pos - start, StandardCharsets.UTF_8);
                pos++;
                return s;
            }
            if (b == '\\') {
                return readEscapedString(start);
            }
            pos++;
        }
        throw new IllegalStateException("Unterminated JSON string");
    }

    private String readEscapedString(int start) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(new String(buf, start, pos - start, StandardCharsets.UTF_8));
        while (pos < len) {
            byte b = buf[pos++];
            if (b == '"') return sb.toString();
            if (b == '\\' && pos < len) {
                byte esc = buf[pos++];
                switch (esc) {
                    case '"', '\\', '/' -> sb.append((char) esc);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = new String(buf, pos, 4, StandardCharsets.US_ASCII);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> sb.append((char) esc);
                }
            } else {
                sb.append((char) b);
            }
        }
        throw new IllegalStateException("Unterminated escaped JSON string");
    }

    private void skipString() {
        expect((byte) '"');
        while (pos < len) {
            byte b = buf[pos++];
            if (b == '"') return;
            if (b == '\\') pos++;
        }
    }

    private void skipValue() {
        skipWhitespace();
        byte b = peek();
        if (b == '"') skipString();
        else if (b == '{') skipObject();
        else if (b == '[') skipArray();
        else {
            while (pos < len) {
                byte c = buf[pos];
                if (c == ',' || c == '}' || c == ']' || c <= ' ') break;
                pos++;
            }
        }
    }

    private void skipObject() {
        expect((byte) '{');
        int depth = 1;
        while (pos < len && depth > 0) {
            byte b = buf[pos++];
            if (b == '"') {
                pos--;
                skipString();
            } else if (b == '{') depth++;
            else if (b == '}') depth--;
        }
    }

    private void skipArray() {
        expect((byte) '[');
        int depth = 1;
        while (pos < len && depth > 0) {
            byte b = buf[pos++];
            if (b == '"') {
                pos--;
                skipString();
            } else if (b == '[') depth++;
            else if (b == ']') depth--;
        }
    }

    private void skipWhitespace() {
        while (pos < len && buf[pos] <= ' ') pos++;
    }

    private byte peek() {
        return pos < len ? buf[pos] : 0;
    }

    private void expect(byte expected) {
        if (pos >= len || buf[pos] != expected) {
            throw new IllegalStateException("Expected '" + (char) expected + "' at byte offset " + pos);
        }
        pos++;
    }
}