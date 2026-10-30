package dev.mobilehorizon.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Minimal generic NBT codec that merges launcher favorites into Minecraft's servers.dat without discarding unknown tags. */
final class MinecraftServerList {
    static final class Server {
        final String name;
        final String address;
        Server(String name, String address) { this.name = name; this.address = address; }
    }
    private static final int MAX_FILE = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 64 * 1024 * 1024;
    private static final int MAX_TAGS = 200000;

    private static final class Tag {
        byte type; String name; Object value;
        Tag(byte type, String name, Object value) { this.type = type; this.name = name; this.value = value; }
    }
    private static final class NbtList {
        byte type; ArrayList<Object> values = new ArrayList<>();
        NbtList(byte type) { this.type = type; }
    }
    private static final class Counter { int tags; }

    private MinecraftServerList() {}

    static synchronized void merge(File file, List<Server> favorites) throws IOException {
        Tag root;
        if (file.isFile() && file.length() > 0) {
            if (file.length() > MAX_FILE) throw new IOException("servers.dat excede o limite de leitura.");
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new BoundedInputStream(new GZIPInputStream(new FileInputStream(file)), MAX_EXPANDED)))) {
                Counter counter = new Counter();
                byte type = in.readByte();
                if (type != 10) throw new IOException("servers.dat não contém um compound NBT.");
                root = new Tag(type, in.readUTF(), readPayload(type, in, 0, counter));
                if (in.read() != -1) throw new IOException("servers.dat possui dados extras.");
            } catch (Exception error) {
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("Não foi possível ler servers.dat sem perder dados existentes.", error);
            }
        } else {
            root = new Tag((byte) 10, "", new ArrayList<Tag>());
        }
        @SuppressWarnings("unchecked") ArrayList<Tag> children = (ArrayList<Tag>) root.value;
        Tag serverTag = find(children, "servers");
        if (serverTag == null) {
            NbtList list = new NbtList((byte) 10);
            serverTag = new Tag((byte) 9, "servers", list);
            children.add(serverTag);
        }
        if (serverTag.type != 9) throw new IOException("A tag servers de servers.dat não é uma lista.");
        NbtList list = (NbtList) serverTag.value;
        if (list.type == 0 && list.values.isEmpty()) list.type = 10;
        if (list.type != 10) throw new IOException("A lista servers possui formato inesperado.");
        for (Server favorite : favorites) {
            @SuppressWarnings("unchecked") ArrayList<Tag> entry = findServer(list, favorite.address);
            if (entry == null) {
                entry = new ArrayList<>();
                list.values.add(entry);
            }
            putString(entry, "name", favorite.name);
            putString(entry, "ip", favorite.address);
            putByte(entry, "acceptTextures", (byte) 1);
        }
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Não foi possível criar a pasta do perfil.");
        File temp = new File(parent, "servers.dat.part");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp, false))))) {
            out.writeByte(10); out.writeUTF(root.name); writePayload((byte) 10, root.value, out, 0);
            out.flush();
        } catch (Exception error) { temp.delete(); if (error instanceof IOException) throw (IOException) error; throw new IOException("Falha ao gravar servers.dat.", error); }
        try (FileOutputStream sync = new FileOutputStream(temp, true)) { sync.getFD().sync(); }
        try {
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

    private static ArrayList<Tag> findServer(NbtList list, String address) throws IOException {
        for (Object value : list.values) {
            @SuppressWarnings("unchecked") ArrayList<Tag> entry = (ArrayList<Tag>) value;
            Tag ip = find(entry, "ip");
            if (ip != null && ip.type == 8 && address.equalsIgnoreCase((String) ip.value)) return entry;
        }
        return null;
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

    private static Object readPayload(byte type, DataInputStream in, int depth, Counter c) throws IOException {
        if (depth > 64 || ++c.tags > MAX_TAGS) throw new IOException("NBT excede os limites de segurança.");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { int n = bounded(in.readInt(), MAX_FILE); byte[] a = new byte[n]; in.readFully(a); return a; }
            case 8: return in.readUTF();
            case 9: {
                byte child = in.readByte(); int n = bounded(in.readInt(), MAX_TAGS);
                NbtList list = new NbtList(child);
                for (int i = 0; i < n; i++) list.values.add(readPayload(child, in, depth + 1, c));
                return list;
            }
            case 10: {
                ArrayList<Tag> tags = new ArrayList<>();
                while (true) {
                    byte child = in.readByte(); if (child == 0) break;
                    if (child < 1 || child > 12) throw new IOException("Tipo de tag NBT desconhecido.");
                    String name = in.readUTF();
                    tags.add(new Tag(child, name, readPayload(child, in, depth + 1, c)));
                }
                return tags;
            }
            case 11: { int n = bounded(in.readInt(), MAX_TAGS); int[] a = new int[n]; for (int i=0;i<n;i++) a[i]=in.readInt(); return a; }
            case 12: { int n = bounded(in.readInt(), MAX_TAGS); long[] a = new long[n]; for (int i=0;i<n;i++) a[i]=in.readLong(); return a; }
            default: throw new IOException("Tipo NBT inválido: " + type);
        }
    }

    private static void writePayload(byte type, Object value, DataOutputStream out, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT profundo demais para gravar.");
        switch (type) {
            case 1: out.writeByte((Byte)value); break;
            case 2: out.writeShort((Short)value); break;
            case 3: out.writeInt((Integer)value); break;
            case 4: out.writeLong((Long)value); break;
            case 5: out.writeFloat((Float)value); break;
            case 6: out.writeDouble((Double)value); break;
            case 7: { byte[] a=(byte[])value; out.writeInt(a.length); out.write(a); break; }
            case 8: out.writeUTF((String)value); break;
            case 9: {
                NbtList list=(NbtList)value; out.writeByte(list.type); out.writeInt(list.values.size());
                for(Object item:list.values) writePayload(list.type,item,out,depth+1);
                break;
            }
            case 10: {
                @SuppressWarnings("unchecked") ArrayList<Tag> tags=(ArrayList<Tag>)value;
                for(Tag tag:tags){out.writeByte(tag.type);out.writeUTF(tag.name);writePayload(tag.type,tag.value,out,depth+1);}
                out.writeByte(0); break;
            }
            case 11: { int[] a=(int[])value; out.writeInt(a.length); for(int v:a)out.writeInt(v); break; }
            case 12: { long[] a=(long[])value; out.writeInt(a.length); for(long v:a)out.writeLong(v); break; }
            default: throw new IOException("Tipo NBT inválido ao gravar: " + type);
        }
    }


    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;
        BoundedInputStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++consumed > maximum) throw new IOException("servers.dat excede 64 MiB descompactados.");
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                consumed += count;
                if (consumed > maximum) throw new IOException("servers.dat excede 64 MiB descompactados.");
            }
            return count;
        }
    }

    private static int bounded(int n, int max) throws IOException {
        if (n < 0 || n > max) throw new IOException("Tamanho NBT fora do limite.");
        return n;
    }
}
