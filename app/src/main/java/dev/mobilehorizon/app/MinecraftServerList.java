package dev.mobilehorizon.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * NBT codec for Minecraft's servers.dat. The PC Horizon launcher reads and writes
 * this file as an uncompressed named NBT compound; old Android builds wrote GZIP,
 * so those files are still accepted on input and migrated on the next sync.
 */
final class MinecraftServerList {
    static final class Server {
        final String name;
        final String address;
        Server(String name, String address) { this.name = name; this.address = address; }
    }

    private static final int MAX_FILE = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 64 * 1024 * 1024;
    private static final int MAX_TAGS = 200000;
    private static final int MAX_DEPTH = 64;

    private static final class Tag {
        byte type;
        String name;
        Object value;
        Tag(byte type, String name, Object value) {
            this.type = type;
            this.name = name;
            this.value = value;
        }
    }

    private static final class NbtList {
        byte type;
        final ArrayList<Object> values = new ArrayList<>();
        NbtList(byte type) { this.type = type; }
    }

    private static final class Counter { int tags; }

    private MinecraftServerList() { }

    static synchronized void merge(File file, List<Server> favorites) throws IOException {
        merge(file, favorites, Collections.emptySet());
    }

    /**
     * Reconciles only the entries previously synchronized by this launcher. Other
     * servers, including hidden Direct Connection entries, are retained unchanged.
     */
    static synchronized void merge(File file, List<Server> favorites, Set<String> previouslySynced)
            throws IOException {
        if (file == null) throw new IOException("O caminho de servers.dat não foi definido.");
        Tag root;
        if (file.isFile() && file.length() > 0) {
            if (file.length() > MAX_FILE) {
                throw new IOException("servers.dat excede o limite de leitura de 16 MiB.");
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    openNbtInput(file)))) {
                Counter counter = new Counter();
                byte type = in.readByte();
                if (type != 10) throw new IOException("servers.dat não contém um compound NBT.");
                root = new Tag(type, readNbtString(in), readPayload(type, in, 0, counter));
                if (in.read() != -1) throw new IOException("servers.dat possui dados extras.");
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Não foi possível ler servers.dat sem perder os dados existentes.", error);
            }
        } else {
            root = new Tag((byte) 10, "", new ArrayList<Tag>());
        }

        @SuppressWarnings("unchecked")
        ArrayList<Tag> children = (ArrayList<Tag>) root.value;
        Tag serverTag = find(children, "servers");
        if (serverTag == null) {
            NbtList list = new NbtList((byte) 10);
            serverTag = new Tag((byte) 9, "servers", list);
            children.add(serverTag);
        }
        if (serverTag.type != 9) {
            throw new IOException("A tag servers de servers.dat não é uma lista.");
        }
        NbtList list = (NbtList) serverTag.value;
        if (list.type == 0 && list.values.isEmpty()) list.type = 10;
        if (list.type != 10) {
            throw new IOException("A lista servers possui formato inesperado.");
        }

        Map<String, Server> desired = new LinkedHashMap<>();
        if (favorites != null) {
            for (Server favorite : favorites) {
                if (favorite == null || favorite.address == null || favorite.address.trim().isEmpty()) {
                    continue;
                }
                String address = favorite.address.trim();
                String normalized = normalizeAddress(address);
                if (normalized.isEmpty()) continue;
                String name = favorite.name == null || favorite.name.trim().isEmpty()
                        ? "Minecraft Server" : favorite.name.trim();
                desired.put(normalized, new Server(name, address));
            }
        }
        Set<String> oldManaged = previouslySynced == null
                ? Collections.emptySet() : previouslySynced;

        // First reconcile existing entries. The sidecar address set remains reliable even
        // if Minecraft itself rewrites servers.dat and strips the launcher's private marker.
        for (int i = 0; i < list.values.size();) {
            @SuppressWarnings("unchecked")
            ArrayList<Tag> entry = (ArrayList<Tag>) list.values.get(i);
            Tag ipTag = find(entry, "ip");
            String address = ipTag != null && ipTag.type == 8 ? (String) ipTag.value : "";
            String normalized = normalizeAddress(address);
            Tag marker = find(entry, "mobilehorizonFavorite");
            boolean marked = marker != null && marker.type == 1 && ((Byte) marker.value) != 0;
            boolean managed = marked || oldManaged.contains(normalized);
            boolean hidden = isHidden(entry);
            Server favorite = desired.remove(normalized);

            if (favorite != null && (!hidden || managed)) {
                putString(entry, "name", favorite.name);
                putString(entry, "ip", favorite.address);
                putByte(entry, "hidden", (byte) 0);
                putByte(entry, "mobilehorizonFavorite", (byte) 1);
                i++;
            } else if (managed) {
                list.values.remove(i);
            } else {
                i++;
            }
        }

        // Add remaining favorites before hidden Direct Connection entries, as PC Horizon does.
        for (Server favorite : desired.values()) {
            ArrayList<Tag> entry = new ArrayList<>();
            putString(entry, "name", favorite.name);
            putString(entry, "ip", favorite.address);
            putByte(entry, "hidden", (byte) 0);
            putByte(entry, "mobilehorizonFavorite", (byte) 1);
            list.values.add(firstHiddenIndex(list), entry);
        }

        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Não foi possível criar a pasta do perfil.");
        }
        File temp = new File(parent, "servers.dat_new");
        File backup = new File(parent, "servers.dat_old");
        if (temp.exists() && !temp.delete()) {
            throw new IOException("Não foi possível remover servers.dat_new antigo.");
        }

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(temp, false)))) {
            out.writeByte(10);
            writeNbtString(out, root.name);
            writePayload((byte) 10, root.value, out, 0);
            out.flush();
        } catch (Exception error) {
            temp.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Falha ao gravar servers.dat em NBT sem compressão.", error);
        }

        try (FileOutputStream sync = new FileOutputStream(temp, true)) {
            sync.getFD().sync();
        }

        try {
            // Preserve the exact previous file, including an older GZIP file, just as
            // the PC launcher keeps servers.dat_old before replacing servers.dat.
            if (file.isFile()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            temp.delete();
            throw new IOException("Não foi possível ativar a lista atualizada de servidores.", error);
        }
    }

    private static InputStream openNbtInput(File file) throws IOException {
        BufferedInputStream raw = new BufferedInputStream(new FileInputStream(file));
        raw.mark(2);
        int first = raw.read();
        int second = raw.read();
        raw.reset();
        if (first == 0x1f && second == 0x8b) {
            return new BoundedInputStream(new GZIPInputStream(raw), MAX_EXPANDED);
        }
        return new BoundedInputStream(raw, MAX_EXPANDED);
    }

    private static int firstHiddenIndex(NbtList list) throws IOException {
        for (int i = 0; i < list.values.size(); i++) {
            @SuppressWarnings("unchecked")
            ArrayList<Tag> entry = (ArrayList<Tag>) list.values.get(i);
            Tag hidden = find(entry, "hidden");
            if (hidden != null && hidden.type == 1 && ((Byte) hidden.value) != 0) return i;
        }
        return list.values.size();
    }

    private static boolean isHidden(ArrayList<Tag> entry) {
        Tag hidden = find(entry, "hidden");
        return hidden != null && hidden.type == 1 && ((Byte) hidden.value) != 0;
    }

    private static String normalizeAddress(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(":25565")) return value.substring(0, value.length() - 6);
        return value;
    }

    private static Tag find(ArrayList<Tag> tags, String name) {
        for (Tag tag : tags) if (name.equals(tag.name)) return tag;
        return null;
    }

    private static void putString(ArrayList<Tag> tags, String name, String value) {
        Tag tag = find(tags, name);
        if (tag == null) tags.add(new Tag((byte) 8, name, value));
        else { tag.type = 8; tag.value = value; }
    }

    private static void putByte(ArrayList<Tag> tags, String name, byte value) {
        Tag tag = find(tags, name);
        if (tag == null) tags.add(new Tag((byte) 1, name, value));
        else { tag.type = 1; tag.value = value; }
    }

    /** NBT strings use unsigned-short byte lengths and standard UTF-8, not Java modified UTF-8. */
    private static String readNbtString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNbtString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("String NBT excede 65535 bytes.");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static Object readPayload(byte type, DataInputStream in, int depth, Counter counter)
            throws IOException {
        if (depth > MAX_DEPTH || ++counter.tags > MAX_TAGS) {
            throw new IOException("NBT excede os limites de segurança.");
        }
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int length = bounded(in.readInt(), MAX_FILE);
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                return bytes;
            }
            case 8: return readNbtString(in);
            case 9: {
                byte childType = in.readByte();
                int length = bounded(in.readInt(), MAX_TAGS);
                if (childType < 0 || childType > 12 || (childType == 0 && length != 0)) {
                    throw new IOException("Tipo inválido na lista NBT.");
                }
                NbtList list = new NbtList(childType);
                for (int i = 0; i < length; i++) {
                    if (childType == 0) throw new IOException("Lista NBT não pode conter TAG_End.");
                    list.values.add(readPayload(childType, in, depth + 1, counter));
                }
                return list;
            }
            case 10: {
                ArrayList<Tag> tags = new ArrayList<>();
                while (true) {
                    byte childType = in.readByte();
                    if (childType == 0) break;
                    if (childType < 1 || childType > 12) {
                        throw new IOException("Tipo de tag NBT desconhecido.");
                    }
                    String name = readNbtString(in);
                    tags.add(new Tag(childType, name,
                            readPayload(childType, in, depth + 1, counter)));
                }
                return tags;
            }
            case 11: {
                int length = bounded(in.readInt(), MAX_TAGS);
                int[] values = new int[length];
                for (int i = 0; i < length; i++) values[i] = in.readInt();
                return values;
            }
            case 12: {
                int length = bounded(in.readInt(), MAX_TAGS);
                long[] values = new long[length];
                for (int i = 0; i < length; i++) values[i] = in.readLong();
                return values;
            }
            default: throw new IOException("Tipo NBT inválido: " + type);
        }
    }

    private static void writePayload(byte type, Object value, DataOutputStream out, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT profundo demais para gravar.");
        switch (type) {
            case 1: out.writeByte((Byte) value); break;
            case 2: out.writeShort((Short) value); break;
            case 3: out.writeInt((Integer) value); break;
            case 4: out.writeLong((Long) value); break;
            case 5: out.writeFloat((Float) value); break;
            case 6: out.writeDouble((Double) value); break;
            case 7: {
                byte[] bytes = (byte[]) value;
                out.writeInt(bytes.length);
                out.write(bytes);
                break;
            }
            case 8: writeNbtString(out, (String) value); break;
            case 9: {
                NbtList list = (NbtList) value;
                out.writeByte(list.type);
                out.writeInt(list.values.size());
                for (Object item : list.values) writePayload(list.type, item, out, depth + 1);
                break;
            }
            case 10: {
                @SuppressWarnings("unchecked")
                ArrayList<Tag> tags = (ArrayList<Tag>) value;
                for (Tag tag : tags) {
                    out.writeByte(tag.type);
                    writeNbtString(out, tag.name);
                    writePayload(tag.type, tag.value, out, depth + 1);
                }
                out.writeByte(0);
                break;
            }
            case 11: {
                int[] values = (int[]) value;
                out.writeInt(values.length);
                for (int item : values) out.writeInt(item);
                break;
            }
            case 12: {
                long[] values = (long[]) value;
                out.writeInt(values.length);
                for (long item : values) out.writeLong(item);
                break;
            }
            default: throw new IOException("Tipo NBT inválido ao gravar: " + type);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++consumed > maximum) {
                throw new IOException("servers.dat excede 64 MiB descompactados.");
            }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                consumed += count;
                if (consumed > maximum) {
                    throw new IOException("servers.dat excede 64 MiB descompactados.");
                }
            }
            return count;
        }
    }

    private static int bounded(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Tamanho NBT fora do limite.");
        return value;
    }
}
