package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-instance manager for Horizon's build-time native .hnmod bundles. */
public final class NativeModsActivity extends Activity {
    public static final String EXTRA_INSTANCE_ID = "instance_id";
    private static final int PICK_HNMOD = 7101;
    private static final int PICK_RUNTIME = 7102;
    private static final long MAX_HNMOD_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_RUNTIME_ARTIFACT_BYTES = 301L * 1024L * 1024L;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String instanceId;
    private TextView status;
    private TextView profileNotice;
    private LinearLayout list;
    private Button importButton;
    private Button buildButton;
    private Button installButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LauncherButtonStyle.install(this);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        instanceId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INSTANCE_ID);
        if (instanceId == null || !GameInstanceManager.isValidInstanceId(instanceId)) {
            finish();
            return;
        }
        buildUi();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  NATIVE MODS", 11, 0xff9e88ff, true), params(-1, -2));
        page.addView(text("Módulos nativos (.hnmod)", 25, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Instância: " + instanceId, 12, 0xffaaa2c6, true), top(-1, -2, 5));
        page.addView(text(
                "O Horizon compila os .hnmod dentro de uma cópia do motor. Módulos que declaram hooks podem usar revisões posteriores se a API instalada for suficiente; módulos sem essa declaração exigem o commit Horizon exato. Cada conjunto selecionado precisa de um runtime ARM64 próprio para esta instância.",
                13, 0xffb9b3d1, false), top(-1, -2, 10));
        profileNotice = text("", 12, 0xffffd18a, false);
        profileNotice.setVisibility(View.GONE);
        page.addView(profileNotice, top(-1, -2, 10));

        LinearLayout warning = card();
        warning.addView(text("Aviso de segurança: código nativo", 14, 0xffffc48a, true), params(-1, -2));
        warning.addView(text(
                "Um .hnmod contém código Rust nativo. Ele não é isolado em sandbox e, quando compilado e executado, terá os mesmos privilégios do processo do jogo. Pode acessar arquivos privados do app, rede e memória. Importe somente módulos cujo código e autor você confia.",
                12, 0xffd1c5b5, false), top(-1, -2, 7));
        page.addView(warning, top(-1, -2, 10));

        importButton = button("Importar .hnmod", true, v -> pick(PICK_HNMOD));
        page.addView(importButton, top(-1, 52, 8));

        buildButton = button("Como compilar os módulos", false, v -> showBuildInstructions());
        page.addView(buildButton, top(-1, 48, 8));

        installButton = button("Instalar runtime compilada (.zip)", false, v -> pick(PICK_RUNTIME));
        page.addView(installButton, top(-1, 48, 8));

        status = text("", 12, 0xffc7bce6, false);
        page.addView(status, top(-1, -2, 10));

        page.addView(text("Módulos desta instância", 14, 0xffeee9ff, true), top(-1, -2, 12));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, top(-1, -2, 6));

        Button back = button("Voltar", false, v -> finish());
        page.addView(back, top(-1, 46, 14));
    }

    private void pick(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception error) {
            showError("Não foi possível abrir o seletor de arquivos", error);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        io.execute(() -> {
            Exception failure = null;
            String resultText = "";
            File staged = null;
            try {
                staged = copySelectedUri(uri, requestCode == PICK_HNMOD ? ".hnmod" : ".zip");
                if (requestCode == PICK_HNMOD) {
                    HorizonNativeModArchive.Descriptor descriptor =
                            HorizonNativeModManager.importArchive(this, instanceId, staged);
                    resultText = "Importado: " + descriptor.displayName()
                            + ". O módulo começa desativado; revise a origem antes de ativá-lo.";
                } else if (requestCode == PICK_RUNTIME) {
                    String base = HorizonRuntimeManager.currentVersion(this);
                    if (base == null || base.isEmpty()) {
                        throw new IOException("Instale primeiro uma runtime Horizon limpa.");
                    }
                    String version = HorizonNativeModManager.installBuildArtifact(
                            this, instanceId, staged, base);
                    resultText = "Runtime nativa verificada e instalada: " + version
                            + ". Será usada somente nesta instância na próxima inicialização.";
                } else {
                    throw new IOException("Operação de importação desconhecida.");
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                if (staged != null && staged.exists() && !staged.delete()) {
                    android.util.Log.w("HorizonNativeMods",
                            "Não foi possível remover o arquivo temporário " + staged);
                }
            }
            final Exception error = failure;
            final String message = resultText;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) showError("Não foi possível importar o arquivo", error);
                else status.setText(message);
                refresh();
            });
        });
    }

    private File copySelectedUri(Uri uri, String expectedExtension) throws IOException {
        String displayName = displayName(uri, expectedExtension);
        if (HorizonDocumentName.requiresFilenameExtension(expectedExtension)
                && (displayName == null
                || !displayName.toLowerCase(java.util.Locale.ROOT).endsWith(expectedExtension))) {
            throw new IOException("Selecione um arquivo " + expectedExtension + ".");
        }
        long maxBytes = ".hnmod".equals(expectedExtension)
                ? MAX_HNMOD_BYTES : MAX_RUNTIME_ARTIFACT_BYTES;
        File staging = new File(getCacheDir(), "native-mod-import-" + android.os.Process.myPid()
                + "-" + System.nanoTime() + expectedExtension);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("O provedor não conseguiu abrir o arquivo selecionado.");
            try (FileOutputStream output = new FileOutputStream(staging)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException(".hnmod limitado a 64 MiB; artefato de runtime limitado a 301 MiB.");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            }
        } catch (Exception error) {
            staging.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Falha ao copiar o arquivo selecionado.", error);
        }
        return staging;
    }

    /**
     * SAF providers are inconsistent: DISPLAY_NAME may be null or an opaque label such as
     * "document", while the document ID or URI segment still contains the real filename.
     * Prefer the first candidate that actually has the requested extension.
     */
    private String displayName(Uri uri, String expectedExtension) {
        String providerName = null;
        String documentId = null;
        String lastPathSegment = null;
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) providerName = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        try {
            documentId = DocumentsContract.getDocumentId(uri);
        } catch (Exception ignored) { }
        try {
            lastPathSegment = Uri.decode(uri.getLastPathSegment());
        } catch (Exception ignored) { }
        return HorizonDocumentName.resolve(
                providerName, documentId, lastPathSegment, expectedExtension);
    }

    private void refresh() {
        if (list == null || isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            GameProfileManager.Profile profile =
                    GameProfileManager.getProfile(this, instanceId);
            boolean fabricJavaBridge = profile.isFabricJavaBridge();
            String base = HorizonRuntimeManager.currentVersion(this);
            if (fabricJavaBridge) {
                profileNotice.setText(
                        "Esta instância está usando Fabric Java Bridge. Os .hnmod são extensões nativas do Horizon e não são carregados pelo Java Bridge. A importação fica armazenada; para compilar e executar esses módulos, altere o perfil desta instância para Horizon/Vanilla.");
                profileNotice.setVisibility(View.VISIBLE);
            } else {
                profileNotice.setVisibility(View.GONE);
            }

            List<HorizonNativeModManager.InstalledMod> mods =
                    HorizonNativeModManager.list(this, instanceId);
            boolean hasSelection = false;
            boolean selectedRevisionCompatible = true;
            String firstMismatch = null;
            for (HorizonNativeModManager.InstalledMod item : mods) {
                boolean revisionCompatible = HorizonNativeModManager
                        .isPotentiallyCompatible(item.descriptor, base);
                if (item.enabled) {
                    hasSelection = true;
                    if (!revisionCompatible) {
                        selectedRevisionCompatible = false;
                        if (firstMismatch == null) firstMismatch = item.descriptor.horizonRevision;
                    }
                }
                list.addView(modCard(item, base), top(-1, -2, 7));
            }
            if (mods.isEmpty()) {
                list.addView(text("Nenhum .hnmod importado nesta instância.", 12, 0xffaaa2c6, false),
                        params(-1, -2));
            }

            if (fabricJavaBridge) {
                status.setText(hasSelection
                        ? "Há módulos .hnmod selecionados, mas eles não são aplicados ao perfil Fabric Java Bridge. Altere o loader para Horizon/Vanilla antes de compilar ou iniciar com esses módulos."
                        : "Nenhum .hnmod selecionado. Esta tela mantém os módulos nativos separados dos mods Fabric (.jar).");
            } else if (hasSelection && !selectedRevisionCompatible) {
                String basePrefix = base == null ? "indisponível" : base;
                status.setText("A revisão Horizon do módulo selecionado (" + firstMismatch.substring(0, 12)
                        + "…) não corresponde ao prefixo da runtime instalada (" + basePrefix
                        + "). O compilador exige a revisão completa idêntica; use um .hnmod criado para esta runtime ou uma base compatível.");
            } else if (hasSelection) {
                boolean ready = HorizonNativeModManager.hasBuildForSelection(this, instanceId, base);
                status.setText(ready
                        ? "Há um build associado à versão Horizon instalada e à API de hooks exigida. A validação completa ocorre novamente ao iniciar o jogo."
                        : "Há módulos selecionados, mas ainda não existe um runtime correspondente. O compilador seguirá a regra oficial de hooks do launcher de PC; até instalar o build, a instância inicia com Horizon limpo.");
            } else {
                status.setText("Nenhum módulo nativo está selecionado. A instância usa o runtime Horizon limpo.");
            }
            boolean canBuild = hasSelection && selectedRevisionCompatible
                    && !fabricJavaBridge && base != null && !base.isEmpty();
            buildButton.setEnabled(canBuild);
            installButton.setEnabled(canBuild);
        } catch (Exception error) {
            profileNotice.setVisibility(View.GONE);
            buildButton.setEnabled(false);
            installButton.setEnabled(false);
            status.setText("Não foi possível ler os .hnmod: "
                    + (error.getMessage() == null ? error.toString() : error.getMessage()));
        }
    }

    private View modCard(HorizonNativeModManager.InstalledMod item, String base) {
        LinearLayout box = card();
        box.addView(text(item.descriptor.displayName(), 14, 0xfff0ecff, true), params(-1, -2));
        box.addView(text("ID: " + item.descriptor.id, 11, 0xffaaa2c6, false), top(-1, -2, 3));
        box.addView(text("Horizon: " + item.descriptor.horizonRevision.substring(0, 12)
                + "  •  Mixins: " + item.descriptor.mixinsRevision.substring(0, 12),
                10, 0xffaaa2c6, false), top(-1, -2, 3));
        boolean revisionCompatible = HorizonNativeModManager
                .isPotentiallyCompatible(item.descriptor, base);
        String revisionStatus;
        if (base == null || base.isEmpty()) {
            revisionStatus = "Compatibilidade com a runtime base não verificada";
        } else if (item.descriptor.hooks != null) {
            revisionStatus = "Requer native hooks API v" + item.descriptor.hooks
                    + "; o compilador consultará a API da revisão instalada";
        } else if (revisionCompatible) {
            revisionStatus = "Revisão Horizon compatível com a runtime instalada";
        } else {
            revisionStatus = "Revisão Horizon diferente da runtime base instalada";
        }
        box.addView(text(revisionStatus, 10,
                revisionCompatible ? 0xff9ee0c0 : 0xffffb58f, true), top(-1, -2, 4));
        box.addView(text(item.enabled ? "Selecionado para a próxima compilação" : "Importado e desativado",
                11, item.enabled ? 0xff9ee0c0 : 0xffaaa2c6, true), top(-1, -2, 7));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(actions, top(-1, -2, 8));
        Button toggle = button(item.enabled ? "Desativar" : "Ativar", !item.enabled,
                v -> toggle(item));
        actions.addView(toggle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button remove = button("Remover", false, v -> confirmRemove(item));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        removeParams.leftMargin = dp(7);
        actions.addView(remove, removeParams);
        return box;
    }

    private void toggle(HorizonNativeModManager.InstalledMod item) {
        if (item.enabled) {
            changeEnabled(item, false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Confiar e ativar código nativo?")
                .setMessage("Ativar " + item.descriptor.id + " permite que seu código Rust seja incluído no motor do jogo. Ele não é executado em sandbox e terá os privilégios do app. Continue somente se você revisou e confia no código e na origem do módulo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ativar", (dialog, which) -> changeEnabled(item, true))
                .show();
    }

    private void changeEnabled(HorizonNativeModManager.InstalledMod item, boolean enabled) {
        try {
            HorizonNativeModManager.setEnabled(this, instanceId, item.descriptor.id, enabled);
            status.setText(enabled
                    ? "Módulo selecionado. Será necessário gerar e instalar um runtime para esta combinação."
                    : "Módulo desativado. O runtime anterior não será usado se não corresponder à seleção.");
            refresh();
        } catch (Exception error) {
            showError("Não foi possível alterar o módulo", error);
        }
    }

    private void confirmRemove(HorizonNativeModManager.InstalledMod item) {
        new AlertDialog.Builder(this)
                .setTitle("Remover " + item.descriptor.id + "?")
                .setMessage("O pacote será removido somente desta instância. Outros perfis não serão alterados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover", (dialog, which) -> {
                    try {
                        HorizonNativeModManager.remove(this, instanceId, item.descriptor.id);
                        refresh();
                    } catch (Exception error) {
                        showError("Não foi possível remover o módulo", error);
                    }
                }).show();
    }

    private void showBuildInstructions() {
        String base;
        try {
            base = HorizonRuntimeManager.currentVersion(this);
        } catch (Exception error) {
            showError("Runtime Horizon indisponível", error);
            return;
        }
        if (base == null || base.isEmpty()) {
            showError("Runtime Horizon indisponível",
                    new IOException("Instale primeiro a runtime Horizon limpa."));
            return;
        }
        String baseTag = HorizonNativeModManager.baseRuntimeReleaseTag(base);
        String message = "Build remoto para esta instância:\n\n"
                + "1. Na página Releases do repositório MobileHorizonNative, crie uma release temporária com uma tag única (por exemplo hnmod-input-01) e envie como assets somente os .hnmod que estão selecionados nesta instância.\n\n"
                + "2. O workflow Build Horizon native .hnmod runtime usa a branch feature/fabric-java-bridge-test e confere automaticamente a impressão digital do overlay contra a runtime limpa instalada. Não é necessário informar um SHA de código Android manualmente. Para usar Run workflow na interface do GitHub, a definição precisa primeiro entrar na branch padrão por PR/merge.\n\n"
                + "3. Ao executar o workflow, informe:\n"
                + "   mod_release_tag = tag da release dos .hnmod\n"
                + "   base_runtime_tag = " + baseTag + "\n\n"
                + "4. O workflow valida os pacotes com o parser Rust oficial do Horizon, aplica os mixins e compila uma runtime ARM64 isolada para esta instância.\n\n"
                + "5. Baixe o artifact ZIP mobilehorizon-native-runtime na execução do Actions (não uma release). Depois selecione o ZIP em “Instalar runtime compilada”.\n\n"
                + "A compilação roda em runner isolado, sem segredos e sem alterar a runtime limpa compartilhada. O build só será ativado nesta instância se o manifesto, a revisão Horizon, os módulos selecionados, o SHA-256 e o ELF ARM64 corresponderem exatamente. Módulos que declarem [[post_build]] são recusados nesta primeira versão porque a execução dessas etapas ainda não foi portada para Android.";
        new AlertDialog.Builder(this)
                .setTitle("Compilar .hnmod para esta instância")
                .setMessage(message)
                .setNegativeButton("Fechar", null)
                .setNeutralButton("Copiar tag base", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Horizon runtime tag", baseTag));
                        status.setText("Tag base copiada: " + baseTag);
                    }
                })
                .setPositiveButton("Ver definição do workflow", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://github.com/Ph04/MobileHorizonNative/blob/feature/fabric-java-bridge-test/.github/workflows/horizon-native-mod-build.yml"));
                    startActivity(intent);
                }).show();
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                .setPositiveButton("Fechar", null)
                .show();
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff171622);
        background.setCornerRadius(dp(15));
        background.setStroke(dp(1), 0xff343044);
        box.setBackground(background);
        return box;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button result = new Button(this);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(13);
        result.setOnClickListener(listener);
        result.setTextColor(0xfff0ecff);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? 0xff59469b : 0xff292637);
        background.setCornerRadius(dp(12));
        result.setBackground(background);
        return result;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private LinearLayout.LayoutParams top(int width, int height, int margin) {
        LinearLayout.LayoutParams p = params(width, height);
        p.topMargin = dp(margin);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
