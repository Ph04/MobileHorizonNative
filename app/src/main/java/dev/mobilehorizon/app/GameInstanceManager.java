package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns isolated Minecraft game-home directories.
 *
 * The legacy filesDir/horizon directory remains the immutable "default" profile so
 * existing worlds, settings, packs and logs are never moved or overwritten during migration.
 * New profiles live below filesDir/instances/<id>/game. Prepared Minecraft resources and the
 * downloaded Horizon engine remain shared by all instances.
 */
public final class GameInstanceManager {
    public static final String DEFAULT_ID = "default";
    private static final int FORMAT = 1;
    private static final int MAX_INSTANCES = 12;
    private static final int MAX_NAME_LENGTH = 24;
    private static final String INDEX_FILE = "instances.json";

    public static final class Instance {
        public final String id;
        public final String name;
        public final long createdAt;
        public final long lastPlayed;
        public final File gameHome;

        private Instance(String id, String name, long createdAt, long lastPlayed, File gameHome) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.lastPlayed = lastPlayed;
            this.gameHome = gameHome;
        }

        public boolean isDefault() {
            return DEFAULT_ID.equals(id);
        }
    }

    private static final class Entry {
        String id;
        String name;
        long createdAt;
        long lastPlayed;

        Entry(String id, String name, long createdAt, long lastPlayed) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.lastPlayed = lastPlayed;
        }
    }

    private static final class Catalog {
        final ArrayList<Entry> entries = new ArrayList<>();
        String selectedId = DEFAULT_ID;
    }

    private GameInstanceManager() {}

    public static synchronized List<Instance> listInstances(Context context) throws IOException {
        Catalog catalog = readCatalog(context);
        ArrayList<Instance> result = new ArrayList<>();
        for (Entry entry : catalog.entries) result.add(toInstance(context, entry));
        Collections.sort(result, (left, right) -> {
            if (left.isDefault()) return right.isDefault() ? 0 : -1;
            if (right.isDefault()) return 1;
            return Long.compare(right.createdAt, left.createdAt);
        });
        return Collections.unmodifiableList(result);
    }

    public static synchronized Instance getSelectedInstance(Context context) throws IOException {
        Catalog catalog = readCatalog(context);
        Entry entry = find(catalog, catalog.selectedId);
        if (entry == null) entry = find(catalog, DEFAULT_ID);
        if (entry == null) throw new IOException("A instância padrão não está registrada.");
        return toInstance(context, entry);
    }

    public static synchronized File getSelectedInstanceHome(Context context) throws IOException {
        return getInstanceHome(context, getSelectedInstance(context).id);
    }

    /**
     * Resolve an internal instance ID to its private game directory. Unknown IDs fall back
     * to the existing default profile; callers can never supply an arbitrary filesystem path.
     */
    public static synchronized File getInstanceHome(Context context, String requestedId)
            throws IOException {
        Catalog catalog = readCatalog(context);
        Entry entry = find(catalog, requestedId);
        if (entry == null) entry = find(catalog, catalog.selectedId);
        if (entry == null) entry = find(catalog, DEFAULT_ID);
        if (entry == null) throw new IOException("Não existe uma instância válida do jogo.");
        File home = homeFor(context, entry.id).getCanonicalFile();
        File filesRoot = context.getApplicationContext().getFilesDir().getCanonicalFile();
        if (entry.id.equals(DEFAULT_ID)) {
            if (!"horizon".equals(home.getName()) || !filesRoot.equals(home.getParentFile())) {
                throw new IOException("O caminho do perfil padrão não é válido.");
            }
        } else {
            File profilesRoot = validatedInstancesRoot(context);
            File profileDirectory = new File(profilesRoot, entry.id).getCanonicalFile();
            if (!profileDirectory.getName().equals(entry.id)
                    || !profilesRoot.equals(profileDirectory.getParentFile())
                    || !"game".equals(home.getName())
                    || !profileDirectory.equals(home.getParentFile())) {
                throw new IOException("O caminho da instância saiu do diretório privado esperado.");
            }
        }
        return home;
    }

    public static synchronized Instance createInstance(Context context, String requestedName)
            throws IOException {
        Catalog catalog = readCatalog(context);
        if (catalog.entries.size() >= MAX_INSTANCES) {
            throw new IOException("O limite de " + MAX_INSTANCES + " instâncias foi atingido.");
        }
        String name = validateName(requestedName);
        ensureUniqueName(catalog, name, null);
        String id = "i-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        Entry entry = new Entry(id, name, System.currentTimeMillis(), 0L);
        File home = homeFor(context, id).getCanonicalFile();
        if (!home.mkdirs() && !home.isDirectory()) {
            throw new IOException("Não foi possível criar a pasta da nova instância.");
        }
        catalog.entries.add(entry);
        try {
            writeCatalog(context, catalog);
        } catch (IOException error) {
            deleteTreeSafely(context, new File(instancesRoot(context), id));
            throw error;
        }
        return toInstance(context, entry);
    }

    public static synchronized Instance renameInstance(
            Context context, String instanceId, String requestedName) throws IOException {
        Catalog catalog = readCatalog(context);
        Entry entry = find(catalog, instanceId);
        if (entry == null) throw new IOException("A instância selecionada não existe.");
        String name = validateName(requestedName);
        ensureUniqueName(catalog, name, instanceId);
        entry.name = name;
        writeCatalog(context, catalog);
        return toInstance(context, entry);
    }

    public static synchronized void setSelectedInstance(Context context, String instanceId)
            throws IOException {
        Catalog catalog = readCatalog(context);
        if (find(catalog, instanceId) == null) {
            throw new IOException("Não foi possível selecionar uma instância inexistente.");
        }
        catalog.selectedId = instanceId;
        writeCatalog(context, catalog);
    }

    public static synchronized void markLastPlayed(Context context, String instanceId)
            throws IOException {
        Catalog catalog = readCatalog(context);
        Entry entry = find(catalog, instanceId);
        if (entry == null) throw new IOException("A instância selecionada não existe.");
        entry.lastPlayed = System.currentTimeMillis();
        catalog.selectedId = entry.id;
        writeCatalog(context, catalog);
    }

    /** Deletes only a non-default, non-active profile, after validating its canonical root. */
    public static synchronized void deleteInstance(Context context, String instanceId)
            throws IOException {
        Catalog catalog = readCatalog(context);
        Entry entry = find(catalog, instanceId);
        if (entry == null) throw new IOException("A instância selecionada não existe.");
        if (entry.id.equals(DEFAULT_ID)) {
            throw new IOException("A instância padrão não pode ser removida.");
        }
        if (entry.id.equals(catalog.selectedId)) {
            throw new IOException("Selecione outra instância antes de excluir esta.");
        }
        File profileRoot = new File(instancesRoot(context), entry.id);
        // Remove the profile from the durable catalog first. If file cleanup is interrupted,
        // only an unreferenced orphan remains; the catalog never points at half-deleted data.
        catalog.entries.remove(entry);
        writeCatalog(context, catalog);
        deleteTreeSafely(context, profileRoot);
    }

    static String validateName(String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("O nome deve ter de 1 a "
                    + MAX_NAME_LENGTH + " caracteres.");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("O nome contém caracteres não permitidos.");
            }
        }
        return name;
    }

    static boolean isValidInstanceId(String id) {
        return DEFAULT_ID.equals(id)
                || (id != null && id.matches("i-[0-9a-f]{32}"));
    }

    private static void ensureUniqueName(Catalog catalog, String name, String exceptId)
            throws IOException {
        for (Entry candidate : catalog.entries) {
            if (candidate.id.equals(exceptId)) continue;
            if (candidate.name.equalsIgnoreCase(name)) {
                throw new IOException("Já existe uma instância com esse nome.");
            }
        }
    }

    private static Entry find(Catalog catalog, String id) {
        if (!isValidInstanceId(id)) return null;
        for (Entry entry : catalog.entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    private static Instance toInstance(Context context, Entry entry) throws IOException {
        return new Instance(entry.id, entry.name, entry.createdAt, entry.lastPlayed,
                homeFor(context, entry.id).getCanonicalFile());
    }

    private static File homeFor(Context context, String id) {
        File files = context.getApplicationContext().getFilesDir();
        if (DEFAULT_ID.equals(id)) return new File(files, "horizon");
        return new File(new File(instancesRoot(context), id), "game");
    }

    private static File instancesRoot(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "instances");
    }

    private static File validatedInstancesRoot(Context context) throws IOException {
        File filesRoot = context.getApplicationContext().getFilesDir().getCanonicalFile();
        File requestedRoot = new File(filesRoot, "instances");
        if (!requestedRoot.isDirectory() && !requestedRoot.mkdirs()) {
            throw new IOException("Não foi possível criar o catálogo de instâncias.");
        }
        File canonicalRoot = requestedRoot.getCanonicalFile();
        if (!"instances".equals(canonicalRoot.getName())
                || !filesRoot.equals(canonicalRoot.getParentFile())) {
            throw new IOException("A pasta de instâncias não é um diretório privado válido.");
        }
        return canonicalRoot;
    }

    private static Catalog readCatalog(Context context) throws IOException {
        File root = validatedInstancesRoot(context);
        File index = new File(root, INDEX_FILE);
        if (!index.isFile()) {
            Catalog initial = new Catalog();
            initial.entries.add(new Entry(DEFAULT_ID, "Padrão", System.currentTimeMillis(), 0L));
            writeCatalog(context, initial);
            return initial;
        }

        if (Files.isSymbolicLink(index.toPath())) {
            throw new IOException("O catálogo não pode ser um link simbólico.");
        }
        long indexSize = index.length();
        if (indexSize <= 0L || indexSize > 1024L * 1024L) {
            throw new IOException("O catálogo de instâncias está vazio ou excede 1 MiB.");
        }
        byte[] bytes = Files.readAllBytes(index.toPath());
        try {
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (json.optInt("format", -1) != FORMAT) {
                throw new IOException("Formato de catálogo de instâncias não suportado.");
            }
            JSONArray items = json.optJSONArray("instances");
            if (items == null) throw new IOException("O catálogo de instâncias está incompleto.");
            Catalog catalog = new Catalog();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (!isValidInstanceId(id) || find(catalog, id) != null) continue;
                if (!DEFAULT_ID.equals(id) && catalog.entries.size() >= MAX_INSTANCES - 1) break;
                String name;
                try {
                    name = validateName(item.optString("name", ""));
                } catch (IllegalArgumentException invalidName) {
                    continue;
                }
                long created = Math.max(0L, item.optLong("created_at", 0L));
                long played = Math.max(0L, item.optLong("last_played", 0L));
                catalog.entries.add(new Entry(id, name, created, played));
            }
            if (find(catalog, DEFAULT_ID) == null) {
                catalog.entries.add(0, new Entry(
                        DEFAULT_ID, "Padrão", System.currentTimeMillis(), 0L));
            }
            String selected = json.optString("selected_id", DEFAULT_ID);
            catalog.selectedId = find(catalog, selected) == null ? DEFAULT_ID : selected;
            // Persist safe repairs (missing default or invalid selected ID), without moving
            // or deleting any existing game data.
            if (!selected.equals(catalog.selectedId)
                    || json.optJSONArray("instances").length() != catalog.entries.size()) {
                writeCatalog(context, catalog);
            }
            return catalog;
        } catch (JSONException error) {
            throw new IOException("O catálogo de instâncias não pôde ser lido sem risco.", error);
        }
    }

    private static void writeCatalog(Context context, Catalog catalog) throws IOException {
        File root = validatedInstancesRoot(context);
        JSONObject json = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            json.put("format", FORMAT);
            json.put("selected_id", catalog.selectedId);
            for (Entry entry : catalog.entries) {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("name", entry.name);
                item.put("created_at", entry.createdAt);
                item.put("last_played", entry.lastPlayed);
                items.put(item);
            }
            json.put("instances", items);
        } catch (JSONException impossible) {
            throw new IOException("Não foi possível montar o catálogo de instâncias.", impossible);
        }

        File target = new File(root, INDEX_FILE);
        File temporary = new File(root, INDEX_FILE + ".tmp");
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(temporary.toPath())) {
            throw new IOException("Os arquivos do catálogo não podem ser links simbólicos.");
        }
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write((json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                // The target is already durable; a leftover temp file is harmless.
            }
        }
    }

    private static void deleteTreeSafely(Context context, File target) throws IOException {
        File profilesRoot = validatedInstancesRoot(context);
        File requestedProfile = target.getAbsoluteFile();
        File canonicalProfile = target.getCanonicalFile();
        if (!requestedProfile.getName().matches("i-[0-9a-f]{32}")
                || !profilesRoot.equals(requestedProfile.getParentFile().getCanonicalFile())
                || !requestedProfile.getName().equals(canonicalProfile.getName())
                || !profilesRoot.equals(canonicalProfile.getParentFile())) {
            throw new IOException("O diretório de exclusão não corresponde a esta instância.");
        }
        deleteRecursively(canonicalProfile, canonicalProfile);
    }

    private static void deleteRecursively(File safeProfileRoot, File target) throws IOException {
        // A symbolic link is removed as a link and is never traversed. This prevents a
        // malicious/corrupt profile from redirecting cleanup into another profile.
        if (Files.isSymbolicLink(target.toPath())) {
            if (!target.delete()) throw new IOException("Não foi possível remover um link da instância.");
            return;
        }
        File canonical = target.getCanonicalFile();
        String prefix = safeProfileRoot.getPath() + File.separator;
        if (!canonical.equals(safeProfileRoot) && !canonical.getPath().startsWith(prefix)) {
            throw new IOException("Foi encontrado um caminho externo dentro da instância.");
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Não foi possível listar " + target);
            for (File child : children) deleteRecursively(safeProfileRoot, child);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Não foi possível excluir " + target.getName());
        }
    }
}
