package com.generalsx.generalszh;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs loose game files into a BIG archive, the format the engine loads mods from
 * (Win32BIGFileSystem::openArchiveFile): "BIGF", the archive size (little endian, not
 * read by the engine), the file count and the header size (both big endian), then per
 * file its data offset and size (big endian) and its NUL-terminated path with
 * backslashes, followed by the file data.
 *
 * The engine's -mod folder only loads *.big, so a custom mod that ships loose files
 * (Data\INI\..., Art\Textures\...) is packed with this before use.
 */
final class BigArchiveWriter {
    private BigArchiveWriter() {}

    /** Packs every file under {@code root} (paths relative to it) into {@code out}. */
    static int pack(File root, List<File> files, File out) throws IOException {
        List<byte[]> names = new ArrayList<>();
        long headerSize = 16;
        for (File f : files) {
            String rel = root.toURI().relativize(f.toURI()).getPath().replace('/', '\\');
            byte[] name = (rel + '\0').getBytes(StandardCharsets.US_ASCII);
            names.add(name);
            headerSize += 8 + name.length;
        }
        long total = headerSize;
        for (File f : files) {
            total += f.length();
        }
        if (total > 0xFFFFFFFFL) {
            throw new IOException("Mod too large for one BIG archive");
        }

        try (OutputStream o = new FileOutputStream(out)) {
            o.write(new byte[] { 'B', 'I', 'G', 'F' });
            writeLE(o, total);
            writeBE(o, files.size());
            writeBE(o, headerSize);
            long offset = headerSize;
            for (int i = 0; i < files.size(); i++) {
                writeBE(o, offset);
                writeBE(o, files.get(i).length());
                o.write(names.get(i));
                offset += files.get(i).length();
            }
            byte[] buf = new byte[256 * 1024];
            for (File f : files) {
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
            }
        }
        return files.size();
    }

    private static void writeBE(OutputStream o, long v) throws IOException {
        o.write((int) (v >>> 24) & 0xFF);
        o.write((int) (v >>> 16) & 0xFF);
        o.write((int) (v >>> 8) & 0xFF);
        o.write((int) v & 0xFF);
    }

    private static void writeLE(OutputStream o, long v) throws IOException {
        o.write((int) v & 0xFF);
        o.write((int) (v >>> 8) & 0xFF);
        o.write((int) (v >>> 16) & 0xFF);
        o.write((int) (v >>> 24) & 0xFF);
    }
}
