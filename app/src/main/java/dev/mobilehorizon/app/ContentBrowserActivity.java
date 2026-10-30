package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Curated Modrinth browser. Resource packs and shaders install directly; modpacks are accepted
 * only when their manifest is Minecraft-only and they contain no mods or loader dependencies.
 */
public final class ContentBrowserActivity extends Activity {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String CDN = "https://cdn.modrinth.com/";
    private static final long MAX_DOWNLOAD = 220L * 1024L * 1024L;
    private static final long MAX_PACK_EXPANDED = 512L * 1024L * 1024L;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private LinearLayout tabs;
    private TextView status;
    private ProgressBar progress;
    private EditText query;
    private String projectType = "resourcepack";
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        search();
    }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(16), dp(14), dp(24)); scroll.addView(page); setContentView(scroll);
        page.addView(text("MOBILEHORIZON  /  CONTEÚDO", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Explorar conteúdo", 25, 0xfff0ecff, true), top(-1, -2, 4));
        page.addView(text("Catálogo Modrinth para Minecraft 26.3. Instalação segura de pacotes de recursos e shaders; modpacks somente sem mods/carregadores.", 12, 0xffb9b3d1, false), top(-1, -2, 5));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 50, 9));
        tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); page.addView(tabs, top(-1, -2, 8));
        addTab(tabs, "Resource packs", "resourcepack");
        addTab(tabs, "Shaders", "shader");
        addTab(tabs, "Modpacks vanilla", "modpack");
        query = new EditText(this); query.setSingleLine(true); query.setTextColor(0xfff0ecff);
        query.setHintTextColor(0xff827b9f); query.setHint("Pesquisar no Modrinth…");
        query.setBackgroundColor(0xff151321); page.addView(query, top(-1, 48, 8));
        page.addView(button("Pesquisar", true, v -> search()), top(-1, 48, 6));
        status = text("", 11, 0xffc7bce6, false); page.addView(status, top(-1, -2, 7));
        progress = new ProgressBar(this); progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        page.addView(progress, top(-1, 4, 6));
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); page.addView(results, top(-1, -2, 4));
    }

    private void addTab(LinearLayout parent, String label, String type) {
        Button tab = button(label, projectType.equals(type), v -> {
            projectType = type;
            refreshTabStyles();
            search();
        });
        tab.setTag(type);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p.rightMargin = dp(4); parent.addView(tab, p);
    }

    private void refreshTabStyles() {
        if (tabs == null) return;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            Button tab = (Button) tabs.getChildAt(i);
            boolean selected = projectType.equals(tab.getTag());
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selected ? 0xff5b3fd6 : 0xff2b2641);
            bg.setCornerRadius(dp(5));
            bg.setStroke(dp(1), selected ? 0xff8a74ff : 0xff3d3560);
            tab.setBackground(bg);
        }
    }

    private void search() {
        refreshTabStyles();
        if (busy) return;
        setBusy(true); results.removeAllViews();
        final String term = query.getText().toString().trim();
        final String type = projectType;
        status.setText("Pesquisando versões compatíveis com Minecraft 26.3…");
        io.execute(() -> {
            JSONArray hits = null; Exception failure = null;
            try {
                String facets = "[[\"project_type:" + type + "\"],[\"versions:" + OfficialMinecraftInstaller.VERSION + "\"]]";
                String url = API + "/search?query=" + URLEncoder.encode(term, "UTF-8")
                        + "&facets=" + URLEncoder.encode(facets, "UTF-8") + "&limit=24&index=relevance";
                hits = getArray(url, 6 * 1024 * 1024);
            } catch (Exception error) { failure = error; }
            final JSONArray data = hits; final Exception error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (error != null) { status.setText("Falha na pesquisa: " + message(error)); return; }
                if (data == null || data.length() == 0) { status.setText("Nenhum projeto encontrado para essa categoria e versão."); return; }
                status.setText("Projetos encontrados: " + data.length());
                for (int i = 0; i < data.length(); i++) {
                    JSONObject hit = data.optJSONObject(i); if (hit != null) results.addView(projectCard(hit), top(-1, -2, 8));
                }
            });
        });
    }

    private View projectCard(JSONObject project) {
        LinearLayout card = card();
        String title = project.optString("title", project.optString("slug", "Projeto"));
        card.addView(text(title, 15, 0xfff0ecff, true), lp(-1, -2));
        String description = project.optString("description", "");
        if (description.length() > 180) description = description.substring(0, 177) + "…";
        card.addView(text(description, 11, 0xffaaa2c6, false), top(-1, -2, 4));
        card.addView(text("Downloads: " + project.optLong("downloads", 0), 10, 0xffa8a1c6, false), top(-1, -2, 4));
        String id = project.optString("project_id", "");
        Button install = button("Ver versão e instalar", true, v -> installProject(id, title));
        card.addView(install, top(-1, 46, 8));
        return card;
    }

    private void installProject(String id, String title) {
        if (busy) return;
        if (id.isEmpty()) { showError("O catálogo retornou um projeto sem ID."); return; }
        setBusy(true); status.setText("Localizando versão para " + title + "…");
        final String type = projectType;
        io.execute(() -> {
            Exception failure = null; String installed = null;
            try {
                String url = API + "/project/" + URLEncoder.encode(id, "UTF-8") + "/version?game_versions="
                        + URLEncoder.encode("[\"" + OfficialMinecraftInstaller.VERSION + "\"]", "UTF-8");
                JSONArray versions = getArray(url, 6 * 1024 * 1024);
                if (versions.length() == 0) throw new IllegalStateException("O projeto não possui versão publicada para Minecraft " + OfficialMinecraftInstaller.VERSION + ".");
                JSONObject version = versions.getJSONObject(0);
                JSONObject file = primaryFile(version.getJSONArray("files"));
                String fileUrl = file.optString("url", "");
                String fileName = safeFileName(file.optString("filename", "download.zip"));
                File home = GameInstanceManager.getSelectedInstanceHome(this);
                File staged = download(fileUrl, fileName, file.optJSONObject("hashes"), new File(getCacheDir(), "modrinth-stage"));
                if ("resourcepack".equals(type)) {
                    installed = installSingle(staged, new File(home, "resourcepacks"), fileName);
                } else if ("shader".equals(type)) {
                    installed = installSingle(staged, new File(home, "shaderpacks"), fileName);
                } else {
                    installed = installVanillaModpack(staged, home);
                }
                staged.delete();
            } catch (Exception error) { failure = error; }
            final Exception error = failure; final String done = installed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (error != null) status.setText("Não foi possível instalar: " + message(error));
                else status.setText("Instalado: " + done + " na instância ativa.");
            });
        });
    }

    private JSONObject primaryFile(JSONArray files) throws Exception {
        if (files == null || files.length() == 0) throw new IllegalStateException("A versão não possui arquivo para download.");
        JSONObject fallback = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (file.optBoolean("primary", false)) return file;
        }
        return fallback;
    }

    private String installSingle(File source, File folder, String fileName) throws Exception {
        ensureDir(folder);
        File target = safeChild(folder, fileName);
        File temp = new File(folder, fileName + ".part");
        copyFile(source, temp, MAX_DOWNLOAD);
        replace(temp, target);
        return target.getName();
    }

    private String installVanillaModpack(File source, File home) throws Exception {
        if (!source.getName().toLowerCase(Locale.ROOT).endsWith(".mrpack")) {
            throw new IllegalArgumentException("O modpack não está no formato .mrpack esperado.");
        }
        long expanded = 0; long extractedBytes = 0; int count = 0; JSONObject index;
        try (ZipFile zip = new ZipFile(source)) {
            ZipEntry manifest = zip.getEntry("modrinth.index.json");
            if (manifest == null || manifest.getSize() > 2 * 1024 * 1024) throw new IllegalArgumentException("Manifesto .mrpack ausente ou grande demais.");
            try (InputStream input = zip.getInputStream(manifest); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n; while ((n = input.read(buf)) != -1) { if (out.size() + n > 2 * 1024 * 1024) throw new IllegalArgumentException("Manifesto muito grande."); out.write(buf,0,n); }
                index = new JSONObject(out.toString("UTF-8"));
            }
            if (!"minecraft".equals(index.optString("game"))) throw new IllegalArgumentException("Este arquivo não é um modpack Minecraft.");
            JSONObject dependencies = index.optJSONObject("dependencies");
            if (dependencies == null || !OfficialMinecraftInstaller.VERSION.equals(dependencies.optString("minecraft", ""))) {
                throw new IllegalArgumentException("O modpack não declara Minecraft 26.3.");
            }
            java.util.Iterator<String> keys = dependencies.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"minecraft".equals(key) && !"java".equals(key)) {
                    throw new IllegalArgumentException("Este modpack exige um carregador ou runtime extra (" + key + "). Apenas pacotes vanilla são aceitos.");
                }
            }
            JSONArray files = index.optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.optJSONObject(i); if (item == null) continue;
                String path = cleanArchivePath(item.optString("path", ""));
                if (path.startsWith("mods/") || path.contains("/mods/")) {
                    throw new IllegalArgumentException("Este modpack contém mods. A instalação automática aceita apenas conteúdo vanilla.");
                }
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > 20000) throw new IllegalArgumentException("O modpack contém entradas demais.");
                String path = cleanArchivePath(entry.getName());
                if (path.contains("mods/") && (path.startsWith("overrides/") || path.startsWith("client-overrides/"))) {
                    throw new IllegalArgumentException("O modpack contém uma pasta mods em seus overrides.");
                }
                long size = Math.max(0, entry.getSize());
                if (size > MAX_PACK_EXPANDED || expanded + size > MAX_PACK_EXPANDED) throw new IllegalArgumentException("O conteúdo declarado do modpack excede 512 MiB.");
                expanded += size;
                if (entry.isDirectory()) continue;
                String relative = supportedOverride(path);
                if (relative == null) continue;
                File targetRoot = path.contains("resourcepacks/") ? new File(home, "resourcepacks") : new File(home, "shaderpacks");
                File target = safeChild(targetRoot, relative);
                File parent = target.getParentFile(); ensureDir(parent);
                try (InputStream input = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[16384]; int n; long written = 0;
                    while ((n = input.read(buf)) != -1) {
                        written += n;
                        extractedBytes += n;
                        if (written > MAX_PACK_EXPANDED || extractedBytes > MAX_PACK_EXPANDED)
                            throw new IllegalArgumentException("Arquivos extraídos do modpack excedem 512 MiB.");
                        out.write(buf,0,n);
                    }
                }
            }
            if (files != null) installIndexedVanillaFiles(files, home);
        }
        return "modpack vanilla (resource packs/shaders)";
    }

    private void installIndexedVanillaFiles(JSONArray files, File home) throws Exception {
        if (files.length() > 2000) throw new IllegalArgumentException("O manifesto lista arquivos demais para instalação.");
        long downloadedTotal = 0L;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i); if (item == null) continue;
            String path = cleanArchivePath(item.optString("path", ""));
            String category = path.startsWith("resourcepacks/") ? "resourcepacks"
                    : path.startsWith("shaderpacks/") ? "shaderpacks" : null;
            if (category == null) continue;
            JSONArray downloads = item.optJSONArray("downloads");
            if (downloads == null || downloads.length() == 0) continue;
            String url = downloads.getString(0);
            JSONObject hashes = item.optJSONObject("hashes");
            File staged = download(url, path.substring(path.lastIndexOf('/') + 1), hashes,
                    new File(getCacheDir(), "modrinth-stage"));
            downloadedTotal += staged.length();
            if (downloadedTotal > MAX_PACK_EXPANDED) {
                staged.delete();
                throw new IllegalArgumentException("Os downloads do modpack excedem 512 MiB no total.");
            }
            File target = safeChild(new File(home, category), path.substring(category.length() + 1));
            ensureDir(target.getParentFile());
            File temp = new File(target.getParentFile(), target.getName() + ".part");
            copyFile(staged,temp,MAX_DOWNLOAD); replace(temp,target); staged.delete();
        }
    }

    private String supportedOverride(String path) {
        String[] prefixes = {"overrides/resourcepacks/", "overrides/shaderpacks/",
                "client-overrides/resourcepacks/", "client-overrides/shaderpacks/"};
        for (String prefix : prefixes) if (path.startsWith(prefix)) return path.substring(prefix.length());
        return null;
    }

    private File download(String url, String name, JSONObject hashes, File staging) throws Exception {
        if (url == null || !url.startsWith(CDN)) throw new IllegalArgumentException("O download não pertence ao CDN oficial do Modrinth.");
        ensureDir(staging);
        File temp = new File(staging, safeFileName(name) + ".part");
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
            long total = 0; byte[] buffer = new byte[16384]; int read;
            while ((read = input.read(buffer)) != -1) {
                total += read; if (total > MAX_DOWNLOAD) throw new IllegalArgumentException("O arquivo excede o limite de 220 MiB.");
                digest.update(buffer,0,read); out.write(buffer,0,read);
            }
            out.getFD().sync();
        } catch (Exception error) { temp.delete(); throw error; }
        finally { connection.disconnect(); }
        String expected = hashes == null ? "" : hashes.optString("sha512", "");
        if (expected.isEmpty()) {
            temp.delete();
            throw new IllegalArgumentException("O catálogo não forneceu um hash SHA-512 para validar o arquivo.");
        }
        if (!expected.equalsIgnoreCase(hex(digest.digest()))) {
            temp.delete(); throw new IllegalStateException("O SHA-512 do arquivo não corresponde ao catálogo.");
        }
        File done = new File(staging, safeFileName(name));
        if (done.exists()) done.delete();
        if (!temp.renameTo(done)) { temp.delete(); throw new IllegalStateException("Não foi possível finalizar o download temporário."); }
        return done;
    }

    private JSONArray getArray(String url, int limit) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Modrinth HTTP " + connection.getResponseCode());
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IllegalStateException("A resposta do catálogo excede o limite."); out.write(buffer,0,n); }
            return new JSONArray(out.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }

    private HttpURLConnection open(String url) throws Exception {
        if (!url.startsWith("https://api.modrinth.com/") && !url.startsWith(CDN))
            throw new IllegalArgumentException("Origem de rede não permitida.");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent","MobileHorizonNative/1.0 (Android launcher)");
        c.setRequestProperty("Accept","application/json");
        return c;
    }

    private String cleanArchivePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\")
                || raw.contains("\\") || raw.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Caminho inválido no arquivo .mrpack.");
        String[] parts = raw.split("/");
        for (String part : parts) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) throw new IllegalArgumentException("O modpack contém tentativa de sair da pasta de destino.");
        return raw;
    }

    private File safeChild(File root, String relative) throws Exception {
        String safe=cleanArchivePath(relative);
        File home=GameInstanceManager.getSelectedInstanceHome(this).getCanonicalFile();
        File canonicalRoot=root.getCanonicalFile();
        if(!canonicalRoot.toPath().startsWith(home.toPath()))
            throw new IllegalArgumentException("A pasta de destino aponta para fora da instância ativa.");
        File target=new File(canonicalRoot,safe).getCanonicalFile();
        if(!target.toPath().startsWith(canonicalRoot.toPath())||target.equals(canonicalRoot))
            throw new IllegalArgumentException("Destino fora da pasta permitida.");
        return target;
    }
    private void copyFile(File source, File target, long max) throws Exception {
        try(InputStream in=new java.io.FileInputStream(source);OutputStream out=new FileOutputStream(target)){
            byte[] b=new byte[16384];int n;long total=0;
            while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IllegalArgumentException("Arquivo excede o limite de cópia.");out.write(b,0,n);}
        }
    }
    private void replace(File temp,File target)throws Exception {
        if(target.exists()&&!target.delete()){temp.delete();throw new IllegalStateException("Não foi possível substituir "+target.getName());}
        if(!temp.renameTo(target)){temp.delete();throw new IllegalStateException("Não foi possível ativar "+target.getName());}
    }
    private void ensureDir(File folder)throws Exception {if(folder==null||(!folder.isDirectory()&&!folder.mkdirs()))throw new IllegalStateException("Não foi possível criar a pasta de destino.");}
    private String safeFileName(String name) {
        String value=name==null?"download.zip":name.replaceAll("[^A-Za-z0-9._-]","_");
        while(value.startsWith("."))value=value.substring(1);
        if(value.isEmpty())value="download.zip";
        if(value.length()>120)value=value.substring(0,120);
        return value;
    }
    private String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private void setBusy(boolean value){busy=value;if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.GONE);}
    private String message(Exception e){return e.getMessage()==null?e.toString():e.getMessage();}
    private void showError(String message){new AlertDialog.Builder(this).setMessage(message).setPositiveButton("Fechar",null).show();}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(11),dp(12),dp(11));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff151321);bg.setCornerRadius(dp(7));bg.setStroke(dp(1),0xff37304f);c.setBackground(bg);return c;}
    private Button button(String s,boolean primary,View.OnClickListener action){Button b=new Button(this);b.setAllCaps(false);b.setText(UiLanguage.translate(this,s));b.setTextSize(11);b.setTextColor(Color.WHITE);b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(3),0,dp(3),0);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?0xff5b3fd6:0xff2b2641);bg.setCornerRadius(dp(5));bg.setStroke(dp(1),primary?0xff8a74ff:0xff3d3560);b.setBackground(bg);b.setOnClickListener(action);return b;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(UiLanguage.translate(this,s));t.setTextSize(size);t.setTextColor(color);t.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);return t;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams top(int w,int h,int m){LinearLayout.LayoutParams p=lp(w,h);p.topMargin=dp(m);return p;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
