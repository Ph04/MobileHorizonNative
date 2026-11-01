package dev.mobilehorizon.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Relocates Termux absolute DT_RUNPATH/DT_RPATH entries in ARM64 ELF shared libraries.
 *
 * OpenJDK from Termux is built with RUNPATH entries rooted at
 * /data/data/com.termux/files/usr. MobileHorizon extracts the same package tree under
 * its own files directory, so those absolute paths are stale inside the app process.
 * Relative $ORIGIN entries keep dependencies resolvable after relocation and when
 * libjvm is dlopen'ed from the already-running Android process.
 */
final class ElfDynamicPathPatcher {
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    private static final int ELF64_HEADER_SIZE = 64;
    private static final int ELF64_PROGRAM_HEADER_SIZE = 56;
    private static final int ELF64_DYNAMIC_ENTRY_SIZE = 16;
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_STRTAB = 5;
    private static final long DT_STRSZ = 10;
    private static final long DT_RPATH = 15;
    private static final long DT_RUNPATH = 29;
    private static final int EM_AARCH64 = 183;
    private static final int MAX_RUNPATH_BYTES = 8192;

    private ElfDynamicPathPatcher() {}

    /** Rewrites Termux-prefix search entries in one ELF file. Returns true when it changed. */
    static boolean relocateTermuxRunpath(File library, File runtimeRoot) throws IOException {
        if (library == null || !library.isFile()) {
            throw new IOException("Biblioteca ELF ausente: "
                    + (library == null ? "(caminho nulo)" : library.getAbsolutePath()));
        }
        Path runtimePrefix = new File(runtimeRoot, "usr").getCanonicalFile().toPath();
        Path libraryPath = library.getCanonicalFile().toPath();
        Path libraryDirectory = libraryPath.getParent();
        if (libraryDirectory == null || !libraryPath.startsWith(runtimePrefix)) {
            throw new IOException("Biblioteca fora do runtime Java privado: " + libraryPath);
        }

        try (RandomAccessFile elf = new RandomAccessFile(libraryPath.toFile(), "rw")) {
            ElfLayout layout = readLayout(elf);
            if (layout == null) return false;

            DynamicStrings dynamic = readDynamicStrings(elf, layout);
            if (dynamic == null) return false;

            String original = readCString(elf, dynamic.stringTableFileOffset
                    + dynamic.runpathStringOffset, dynamic.stringTableSize - dynamic.runpathStringOffset);
            if (!original.contains(TERMUX_PREFIX)) return false;

            String[] entries = original.split(":", -1);
            List<String> relocated = new ArrayList<>(entries.length);
            for (String entry : entries) {
                if (entry.equals(TERMUX_PREFIX) || entry.startsWith(TERMUX_PREFIX + "/")) {
                    String suffix = entry.substring(TERMUX_PREFIX.length());
                    while (suffix.startsWith("/")) suffix = suffix.substring(1);
                    Path target = runtimePrefix.resolve(suffix).normalize();
                    if (!target.startsWith(runtimePrefix)) {
                        throw new IOException("DT_RUNPATH escapa do runtime privado: " + entry);
                    }
                    String relative = libraryDirectory.relativize(target)
                            .toString().replace(File.separatorChar, '/');
                    relocated.add("$ORIGIN" + (relative.isEmpty() ? "" : "/" + relative));
                } else {
                    relocated.add(entry);
                }
            }

            String replacement = String.join(":", relocated);
            byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
            if (replacementBytes.length > dynamic.runpathStringLength) {
                throw new IOException("O DT_RUNPATH relocalizado é maior que o espaço original em "
                        + libraryPath + "; não é seguro alterar a tabela dinâmica.");
            }

            long stringOffset = dynamic.stringTableFileOffset + dynamic.runpathStringOffset;
            elf.seek(stringOffset);
            elf.write(replacementBytes);
            // Terminate the replacement. The remaining original bytes are unreferenced
            // by the dynamic string table and are deliberately left unchanged.
            elf.write(0);
            elf.getFD().sync();
            return true;
        }
    }

    /** Returns the active DT_RUNPATH/DT_RPATH string, or null when the ELF has no path tag. */
    static String readRunpath(File library) throws IOException {
        try (RandomAccessFile elf = new RandomAccessFile(library, "r")) {
            ElfLayout layout = readLayout(elf);
            if (layout == null) return null;
            DynamicStrings dynamic = readDynamicStrings(elf, layout);
            if (dynamic == null) return null;
            return readCString(elf, dynamic.stringTableFileOffset + dynamic.runpathStringOffset,
                    dynamic.stringTableSize - dynamic.runpathStringOffset);
        }
    }

    /** Checks whether this ELF's own runpath resolves to the given directory. */
    static boolean runpathIncludesDirectory(File library, File directory) throws IOException {
        String runpath = readRunpath(library);
        if (runpath == null) return false;
        Path expected = directory.getCanonicalFile().toPath();
        Path origin = library.getCanonicalFile().toPath().getParent();
        for (String entry : runpath.split(":", -1)) {
            String resolvedEntry = entry;
            if (resolvedEntry.equals("$ORIGIN")) {
                resolvedEntry = origin.toString();
            } else if (resolvedEntry.startsWith("$ORIGIN/")) {
                resolvedEntry = origin.resolve(resolvedEntry.substring("$ORIGIN/".length())).toString();
            } else if (resolvedEntry.contains("$ORIGIN")) {
                continue;
            }
            try {
                if (new File(resolvedEntry).getCanonicalFile().toPath().equals(expected)) return true;
            } catch (IOException ignored) {
                // Another runpath entry may still resolve the required directory.
            }
        }
        return false;
    }

    private static ElfLayout readLayout(RandomAccessFile elf) throws IOException {
        long fileLength = elf.length();
        if (fileLength < ELF64_HEADER_SIZE) return null;
        byte[] header = new byte[ELF64_HEADER_SIZE];
        elf.seek(0);
        elf.readFully(header);
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return null;
        if ((header[4] & 0xff) != 2 || (header[5] & 0xff) != 1
                || unsignedShort(header, 18) != EM_AARCH64) {
            // Package trees may contain symlinked system libraries or non-runtime helper
            // binaries. Only ARM64 ELF shared objects are candidates for relocation.
            return null;
        }

        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long programHeaderOffset = fields.getLong(32);
        int programHeaderSize = unsignedShort(header, 54);
        int programHeaderCount = unsignedShort(header, 56);
        if (programHeaderOffset < 0 || programHeaderSize < ELF64_PROGRAM_HEADER_SIZE
                || programHeaderCount <= 0 || programHeaderCount > 4096
                || !withinFile(programHeaderOffset,
                        (long) programHeaderSize * programHeaderCount, fileLength)) {
            throw new IOException("Tabela ELF de program headers inválida.");
        }

        List<LoadSegment> loadSegments = new ArrayList<>();
        long dynamicOffset = -1;
        long dynamicSize = -1;
        byte[] programHeader = new byte[programHeaderSize];
        for (int i = 0; i < programHeaderCount; i++) {
            long offset = programHeaderOffset + (long) i * programHeaderSize;
            elf.seek(offset);
            elf.readFully(programHeader);
            ByteBuffer ph = ByteBuffer.wrap(programHeader).order(ByteOrder.LITTLE_ENDIAN);
            int type = ph.getInt(0);
            long fileOffset = ph.getLong(8);
            long virtualAddress = ph.getLong(16);
            long fileSize = ph.getLong(32);
            if (fileOffset < 0 || virtualAddress < 0 || fileSize < 0
                    || !withinFile(fileOffset, fileSize, fileLength)) {
                throw new IOException("Segmento ELF fora do arquivo.");
            }
            if (type == PT_LOAD) {
                loadSegments.add(new LoadSegment(fileOffset, virtualAddress, fileSize));
            } else if (type == PT_DYNAMIC) {
                dynamicOffset = fileOffset;
                dynamicSize = fileSize;
            }
        }
        if (dynamicOffset < 0 || dynamicSize < ELF64_DYNAMIC_ENTRY_SIZE) return null;
        return new ElfLayout(loadSegments, dynamicOffset, dynamicSize, fileLength);
    }

    private static DynamicStrings readDynamicStrings(RandomAccessFile elf, ElfLayout layout)
            throws IOException {
        long stringTableAddress = -1;
        long stringTableSize = -1;
        long runpathStringOffset = -1;
        for (long offset = 0; offset + ELF64_DYNAMIC_ENTRY_SIZE <= layout.dynamicSize;
             offset += ELF64_DYNAMIC_ENTRY_SIZE) {
            byte[] entry = new byte[ELF64_DYNAMIC_ENTRY_SIZE];
            elf.seek(layout.dynamicOffset + offset);
            elf.readFully(entry);
            ByteBuffer fields = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
            long tag = fields.getLong(0);
            long value = fields.getLong(8);
            if (tag == DT_NULL) break;
            if (tag == DT_STRTAB) stringTableAddress = value;
            else if (tag == DT_STRSZ) stringTableSize = value;
            else if (tag == DT_RUNPATH) runpathStringOffset = value;
            else if (tag == DT_RPATH && runpathStringOffset < 0) runpathStringOffset = value;
        }
        if (stringTableAddress < 0 || stringTableSize <= 0 || runpathStringOffset < 0
                || runpathStringOffset >= stringTableSize) return null;

        for (LoadSegment segment : layout.loadSegments) {
            if (stringTableAddress >= segment.virtualAddress) {
                long relative = stringTableAddress - segment.virtualAddress;
                if (relative <= segment.fileSize && stringTableSize <= segment.fileSize - relative) {
                    long fileOffset = segment.fileOffset + relative;
                    if (withinFile(fileOffset, stringTableSize, layout.fileLength)) {
                        String current = readCString(elf, fileOffset + runpathStringOffset,
                                stringTableSize - runpathStringOffset);
                        return new DynamicStrings(fileOffset, stringTableSize, runpathStringOffset,
                                current.getBytes(StandardCharsets.UTF_8).length);
                    }
                }
            }
        }
        throw new IOException("DT_STRTAB não está dentro de um segmento ELF carregável.");
    }

    private static String readCString(RandomAccessFile input, long offset, long maxLength)
            throws IOException {
        if (offset < 0 || maxLength <= 0 || maxLength > Integer.MAX_VALUE) {
            throw new IOException("Offset de string ELF inválido.");
        }
        int limit = (int) Math.min(maxLength, MAX_RUNPATH_BYTES);
        byte[] bytes = new byte[limit];
        input.seek(offset);
        int length = 0;
        while (length < limit) {
            int value = input.read();
            if (value < 0) throw new IOException("String dinâmica ELF truncada.");
            if (value == 0) {
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            bytes[length++] = (byte) value;
        }
        throw new IOException("String DT_RUNPATH ELF não possui terminador válido.");
    }

    private static boolean withinFile(long offset, long size, long fileLength) {
        return offset >= 0 && size >= 0 && offset <= fileLength && size <= fileLength - offset;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static final class LoadSegment {
        final long fileOffset;
        final long virtualAddress;
        final long fileSize;

        LoadSegment(long fileOffset, long virtualAddress, long fileSize) {
            this.fileOffset = fileOffset;
            this.virtualAddress = virtualAddress;
            this.fileSize = fileSize;
        }
    }

    private static final class ElfLayout {
        final List<LoadSegment> loadSegments;
        final long dynamicOffset;
        final long dynamicSize;
        final long fileLength;

        ElfLayout(List<LoadSegment> loadSegments, long dynamicOffset, long dynamicSize,
                  long fileLength) {
            this.loadSegments = loadSegments;
            this.dynamicOffset = dynamicOffset;
            this.dynamicSize = dynamicSize;
            this.fileLength = fileLength;
        }
    }

    private static final class DynamicStrings {
        final long stringTableFileOffset;
        final long stringTableSize;
        final long runpathStringOffset;
        final int runpathStringLength;

        DynamicStrings(long stringTableFileOffset, long stringTableSize,
                       long runpathStringOffset, int runpathStringLength) {
            this.stringTableFileOffset = stringTableFileOffset;
            this.stringTableSize = stringTableSize;
            this.runpathStringOffset = runpathStringOffset;
            this.runpathStringLength = runpathStringLength;
        }
    }
}
