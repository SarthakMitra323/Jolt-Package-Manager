// File: src/main/java/dev/jolt/net/RangeZipReader.java
package dev.jolt.net;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

public final class RangeZipReader {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CEN_SIGNATURE  = 0x02014b50;
    private static final int LOC_SIGNATURE  = 0x04034b50;
    private static final int TAIL_BYTES     = 65536; // 64 KB covers EOCD + Central Dir for 99.5% of wheels

    private RangeZipReader() {}

    public static CompletableFuture<byte[]> fetchMetadataViaRangeAsync(HttpClient client, String wheelUrl) {
        HttpRequest tailReq = HttpRequest.newBuilder(URI.create(wheelUrl))
                .header("Range", "bytes=-" + TAIL_BYTES)
                .header("User-Agent", "jolt/0.1.0")
                .GET()
                .build();

        return client.sendAsync(tailReq, HttpResponse.BodyHandlers.ofByteArray()).orTimeout(15, TimeUnit.SECONDS)
                .thenCompose(resp -> {
                    if (resp.statusCode() != 206) {
                        throw new IllegalStateException("Server does not support HTTP Range requests: " + resp.statusCode());
                    }
                    byte[] tail = resp.body();
                    long totalLength = parseTotalLengthFromContentRange(
                            resp.headers().firstValue("Content-Range").orElseThrow()
                    );
                    long tailStartOffset = totalLength - tail.length;

                    ZipEntryOffset metaEntry = findMetadataEntryInTail(tail, tailStartOffset);
                    // Request local header (30 bytes + up to 256 bytes filename/extra) + compressed payload
                    long rangeStart = metaEntry.localHeaderOffset;
                    long rangeEnd = rangeStart + 286 + metaEntry.compressedSize;

                    HttpRequest sliceReq = HttpRequest.newBuilder(URI.create(wheelUrl))
                            .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                            .header("User-Agent", "jolt/0.1.0")
                            .GET()
                            .build();

                    return client.sendAsync(sliceReq, HttpResponse.BodyHandlers.ofByteArray()).orTimeout(15, TimeUnit.SECONDS)
                            .thenApply(sliceResp -> extractLocalEntry(
                                    sliceResp.body(),
                                    metaEntry.compressionMethod,
                                    metaEntry.compressedSize,
                                    metaEntry.uncompressedSize
                            ));
                });
    }

    private record ZipEntryOffset(
            int compressionMethod,
            int compressedSize,
            int uncompressedSize,
            long localHeaderOffset
    ) {}

    private static ZipEntryOffset findMetadataEntryInTail(byte[] tail, long tailStartOffset) {
        ByteBuffer bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);

        // Scan backwards from end for End of Central Directory (EOCD) signature
        int eocdPos = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (bb.getInt(i) == EOCD_SIGNATURE) {
                eocdPos = i;
                break;
            }
        }
        if (eocdPos == -1) {
            throw new IllegalStateException("ZIP EOCD signature not found in 64KB tail");
        }

        long cdOffset = Integer.toUnsignedLong(bb.getInt(eocdPos + 16));
        int cdPosInTail = (int) (cdOffset - tailStartOffset);
        if (cdPosInTail < 0) {
            throw new IllegalStateException("Central Directory larger than 64KB tail");
        }

        int pos = cdPosInTail;
        while (pos + 46 <= tail.length) {
            if (bb.getInt(pos) != CEN_SIGNATURE) break;

            int method = Short.toUnsignedInt(bb.getShort(pos + 10));
            int compSize = bb.getInt(pos + 20);
            int uncompSize = bb.getInt(pos + 24);
            int nameLen = Short.toUnsignedInt(bb.getShort(pos + 28));
            int extraLen = Short.toUnsignedInt(bb.getShort(pos + 30));
            int commentLen = Short.toUnsignedInt(bb.getShort(pos + 32));
            long localOffset = Integer.toUnsignedLong(bb.getInt(pos + 42));

            String entryName = new String(tail, pos + 46, nameLen, StandardCharsets.UTF_8);
            if (entryName.endsWith(".dist-info/METADATA")) {
                return new ZipEntryOffset(method, compSize, uncompSize, localOffset);
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
        throw new IllegalStateException(".dist-info/METADATA entry not found in wheel Central Directory");
    }

    private static byte[] extractLocalEntry(byte[] slice, int method, int compSize, int uncompSize) {
        ByteBuffer bb = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(0) != LOC_SIGNATURE) {
            throw new IllegalStateException("Invalid ZIP Local File Header signature");
        }
        int nameLen = Short.toUnsignedInt(bb.getShort(26));
        int extraLen = Short.toUnsignedInt(bb.getShort(28));
        int dataOffset = 30 + nameLen + extraLen;

        if (method == 0) { // STORED
            byte[] out = new byte[uncompSize];
            System.arraycopy(slice, dataOffset, out, 0, uncompSize);
            return out;
        } else if (method == 8) { // DEFLATED
            Inflater inflater = new Inflater(true); // nowrap=true for raw ZIP deflate
            try {
                inflater.setInput(slice, dataOffset, compSize);
                byte[] out = new byte[uncompSize];
                int decoded = inflater.inflate(out);
                if (decoded != uncompSize) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompSize);
                    baos.write(out, 0, decoded);
                    byte[] buf = new byte[4096];
                    while (!inflater.finished()) {
                        int n = inflater.inflate(buf);
                        if (n == 0) break;
                        baos.write(buf, 0, n);
                    }
                    return baos.toByteArray();
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException("Failed to inflate remote METADATA entry", e);
            } finally {
                inflater.end();
            }
        }
        throw new UnsupportedOperationException("Unsupported ZIP compression method: " + method);
    }

    private static long parseTotalLengthFromContentRange(String contentRange) {
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) throw new IllegalStateException("Malformed Content-Range: " + contentRange);
        return Long.parseLong(contentRange.substring(slash + 1).trim());
    }
}