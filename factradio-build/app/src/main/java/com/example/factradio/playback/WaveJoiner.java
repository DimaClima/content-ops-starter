package com.example.factradio.playback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

final class WaveJoiner {
    private WaveJoiner() {}

    static void join(List<File> parts, File output) throws IOException {
        if (parts.isEmpty()) throw new IOException("Нет аудиофрагментов");
        ArrayList<WavePart> parsed = new ArrayList<>();
        long totalData = 0;
        for (File file : parts) {
            WavePart part = inspect(file);
            parsed.add(part);
            totalData += part.dataSize;
        }
        if (totalData > Integer.MAX_VALUE) throw new IOException("Подкаст слишком большой");

        WavePart first = parsed.get(0);
        byte[] header = new byte[(int) first.dataOffset];
        try (FileInputStream input = new FileInputStream(first.file)) {
            readFully(input, header);
        }
        writeLittleEndian(header, 4, (int) (header.length + totalData - 8));
        writeLittleEndian(header, first.dataSizeOffset, (int) totalData);

        try (FileOutputStream target = new FileOutputStream(output)) {
            target.write(header);
            byte[] buffer = new byte[32 * 1024];
            for (WavePart part : parsed) {
                try (FileInputStream input = new FileInputStream(part.file)) {
                    long skipped = 0;
                    while (skipped < part.dataOffset) {
                        long count = input.skip(part.dataOffset - skipped);
                        if (count <= 0) throw new IOException("Не удалось прочитать WAV");
                        skipped += count;
                    }
                    long remaining = part.dataSize;
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("WAV оборван");
                        target.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
            }
        }
    }

    private static WavePart inspect(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 44 || !"RIFF".equals(readAscii(input, 4))) {
                throw new IOException("Неизвестный формат WAV");
            }
            input.seek(8);
            if (!"WAVE".equals(readAscii(input, 4))) throw new IOException("Не WAV");
            long cursor = 12;
            while (cursor + 8 <= input.length()) {
                input.seek(cursor);
                String id = readAscii(input, 4);
                int size = readLittleEndian(input);
                if (size < 0) throw new IOException("Повреждённый WAV");
                if ("data".equals(id)) return new WavePart(file, cursor + 8, cursor + 4, size);
                cursor += 8L + size + (size & 1);
            }
        }
        throw new IOException("В WAV нет звуковых данных");
    }

    private static String readAscii(RandomAccessFile input, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readLittleEndian(RandomAccessFile input) throws IOException {
        return input.readUnsignedByte()
                | input.readUnsignedByte() << 8
                | input.readUnsignedByte() << 16
                | input.readUnsignedByte() << 24;
    }

    private static void writeLittleEndian(byte[] bytes, long offset, int value) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw new IOException("Повреждённый WAV-заголовок");
        int index = (int) offset;
        bytes[index] = (byte) value;
        bytes[index + 1] = (byte) (value >>> 8);
        bytes[index + 2] = (byte) (value >>> 16);
        bytes[index + 3] = (byte) (value >>> 24);
    }

    private static void readFully(FileInputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) throw new IOException("Файл WAV оборван");
            offset += count;
        }
    }

    private static final class WavePart {
        final File file;
        final long dataOffset;
        final long dataSizeOffset;
        final long dataSize;

        WavePart(File file, long dataOffset, long dataSizeOffset, long dataSize) {
            this.file = file;
            this.dataOffset = dataOffset;
            this.dataSizeOffset = dataSizeOffset;
            this.dataSize = dataSize;
        }
    }
}
