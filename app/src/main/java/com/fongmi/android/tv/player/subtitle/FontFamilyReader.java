package com.fongmi.android.tv.player.subtitle;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Reads the SFNT name table, including the first face of TrueType collections. */
final class FontFamilyReader {
    static String getFamilyName(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long face = 0;
            if (in.readInt() == 0x74746366) {
                in.readInt();
                long count = Integer.toUnsignedLong(in.readInt());
                if (count == 0 || count > 4096) throw new IOException("Invalid font collection");
                face = Integer.toUnsignedLong(in.readInt());
            }
            checkRange(in, face, 12);
            in.seek(face + 4);
            int tables = in.readUnsignedShort();
            checkRange(in, face + 12, tables * 16L);
            for (int i = 0; i < tables; i++) {
                in.seek(face + 12 + i * 16L);
                int tag = in.readInt();
                in.readInt();
                long offset = Integer.toUnsignedLong(in.readInt());
                long length = Integer.toUnsignedLong(in.readInt());
                if (tag == 0x6e616d65) return readNames(in, offset, length);
            }
            throw new IOException("Font name table is missing");
        }
    }

    private static String readNames(RandomAccessFile in, long offset, long length) throws IOException {
        checkRange(in, offset, length);
        if (length < 6) throw new IOException("Invalid font name table");
        in.seek(offset);
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        int strings = in.readUnsignedShort();
        if (6L + count * 12L > length || strings > length) throw new IOException("Invalid name records");
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < count; i++) {
            in.seek(offset + 6 + i * 12L);
            int platform = in.readUnsignedShort();
            int encoding = in.readUnsignedShort();
            int language = in.readUnsignedShort();
            int name = in.readUnsignedShort();
            int size = in.readUnsignedShort();
            int start = in.readUnsignedShort();
            if ((name != 1 && name != 16) || size == 0 || size > 4096) continue;
            Charset charset;
            if (platform == 0 || (platform == 3 && (encoding == 0 || encoding == 1 || encoding == 10))) charset = StandardCharsets.UTF_16BE;
            else if (platform == 1 && encoding == 0) charset = Charset.forName("x-MacRoman");
            else continue;
            if ((long) strings + start + size > length) throw new IOException("Invalid font name string");
            in.seek(offset + strings + start);
            byte[] bytes = new byte[size];
            in.readFully(bytes);
            String value = new String(bytes, charset).replace("\u0000", "").trim();
            int score = (name == 16 ? 100 : 0) + (platform == 3 ? 20 : platform == 0 ? 10 : 0) + (language == 0x409 ? 5 : 0);
            if (!value.isEmpty() && score > bestScore) { best = value; bestScore = score; }
        }
        if (best == null) throw new IOException("Font family name is missing");
        return best;
    }

    private static void checkRange(RandomAccessFile in, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset > in.length() || length > in.length() - offset) throw new IOException("Invalid font data range");
    }
}
