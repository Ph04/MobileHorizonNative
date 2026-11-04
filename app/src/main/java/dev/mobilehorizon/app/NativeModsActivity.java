package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
    private static final long MAX_RUNTIME_ARTIFACT_BYTES = 440L * 1024L * 1024L;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String instanceId;
    private TextView status;
    private TextView profileNotice;
    private LinearLayout list;
    private Button importButton;
    private Button buildButton;
    private Button installButton;
    private Button githubButton;
    private boolean buildEligible;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable buildStateTicker = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            refreshBuildState();
            uiHandler.postDelayed(this, 2000L);
        }
    };

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
        uiHandler.removeCallbacks(buildStateTicker);
        uiHandler.post(buildStateTicker);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(buildStateTicker);
        super.onPause();
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

        githubButton = button("Conectar ao GitHub", false, v -> showGitHubTokenDialog(null));
        page.addView(githubButton, top(-1, 48, 8));

        buildButton = button("Compilar módulos selecionados no app", true, v -> beginRemoteBuild());
        page.addView(buildButton, top(-1, 50, 8));

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
                        throw new IOException(".hnmod limitado a 64 MiB; artifact de runtime com dados gerados limitado a 440 MiB.");
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
            String firstMismatchId = null;
            for (HorizonNativeModManager.InstalledMod item : mods) {
                boolean revisionCompatible = HorizonNativeModManager
                        .isPotentiallyCompatible(item.descriptor, base);
                if (item.enabled) {
                    hasSelection = true;
                    if (!revisionCompatible) {
                        selectedRevisionCompatible = false;
                        if (firstMismatch == null) {
                            firstMismatch = item.descriptor.horizonRevision;
                            firstMismatchId = item.descriptor.id;
                        }
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
                status.setText("O módulo " + firstMismatchId + " exige exatamente Horizon "
                        + firstMismatch.substring(0, 12) + "…; a runtime desta instância é "
                        + basePrefix + ". Como o pacote não declara uma API de hooks compatível, "
                        + "não é seguro repiná-lo automaticamente. Desative-o ou importe uma versão "
                        + "do módulo atualizada para esta revisão do Horizon.");
            } else if (hasSelection) {
                boolean ready = HorizonNativeModManager.hasBuildForSelection(this, instanceId, base);
                status.setText(ready
                        ? "Há um build associado à versão Horizon instalada e à API de hooks exigida. A validação completa ocorre novamente ao iniciar o jogo."
                        : "Há módulos selecionados, mas ainda não existe um runtime correspondente. Para revisões antigas sem hooks declarados, o CI precisa provar que o commit é ancestral da base e recompilar todas as injeções. Se qualquer assinatura ou binding não corresponder, o build será recusado. Até instalar um build válido, a instância inicia com Horizon limpo.");
            } else {
                status.setText("Nenhum módulo nativo está selecionado. A instância usa o runtime Horizon limpo.");
            }
            buildEligible = hasSelection && selectedRevisionCompatible
                    && !fabricJavaBridge && base != null && !base.isEmpty();
            buildButton.setEnabled(buildEligible
                    && !NativeModBuildService.isRunning(this, instanceId));
            githubButton.setText(GitHubTokenStore.hasToken(this)
                    ? "Gerenciar acesso GitHub" : "Conectar ao GitHub");
            installButton.setEnabled(buildEligible);
        } catch (Exception error) {
            profileNotice.setVisibility(View.GONE);
            buildEligible = false;
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
        } else if (HorizonNativeModManager.matchesBaseRuntimeRevision(
                item.descriptor.horizonRevision, base)) {
            revisionStatus = "Pin Horizon exato da runtime instalada";
        } else if (revisionCompatible) {
            revisionStatus = "Revisão anterior: o build verificará ancestralidade e recompilará as injeções";
        } else {
            revisionStatus = "Revisão Horizon inválida para o Minecraft/runtime selecionado";
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

    private void beginRemoteBuild() {
        final String token;
        try {
            token = GitHubTokenStore.load(this);
        } catch (Exception error) {
            showError("Acesso GitHub indisponível", error);
            return;
        }
        if (token == null || token.isEmpty()) {
            showGitHubTokenDialog(this::confirmRemoteBuild);
            return;
        }
        confirmRemoteBuild();
    }

    private void showGitHubTokenDialog(Runnable afterSave) {
        boolean configured = GitHubTokenStore.hasToken(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView help = text(
                "Crie um Fine-grained personal access token no GitHub. Limite o acesso ao repositório "
                        + "Ph04/MobileHorizonNative e configure somente: Contents (Read and write) e "
                        + "Actions (Read and write). Metadata (Read-only) é concedido automaticamente. "
                        + "Use uma validade curta, como 7 dias.\n\n"
                        + "O token fica cifrado pelo Android Keystore deste aparelho. Ele não é enviado "
                        + "ao nosso servidor nem gravado em logs. O app o usa apenas para criar a Release "
                        + "temporária, iniciar a Action e baixar o artefato.",
                12, 0xffc7bce6, false);
        content.addView(help, params(-1, -2));
        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setTextSize(13);
        tokenInput.setHint(configured
                ? "Token já configurado; cole outro para substituir"
                : "github_pat_…");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(tokenInput, top(-1, 50, 12));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(configured ? "Gerenciar acesso GitHub" : "Conectar ao GitHub")
                .setView(content)
                .setPositiveButton("Salvar token", null)
                .setNeutralButton("Criar token no GitHub", null)
                .setNegativeButton(configured ? "Remover token" : "Fechar", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = tokenInput.getText() == null ? "" : tokenInput.getText().toString();
                try {
                    GitHubTokenStore.save(this, value);
                    dialog.dismiss();
                    status.setText("Token salvo com criptografia do Android Keystore.");
                    refresh();
                    if (afterSave != null) afterSave.run();
                } catch (Exception error) {
                    showError("Não foi possível salvar o token", error);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://github.com/settings/personal-access-tokens/new")));
                } catch (Exception error) {
                    showError("Não foi possível abrir o GitHub", error);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (!configured) {
                    dialog.dismiss();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Remover token GitHub?")
                        .setMessage("O token cifrado e a chave local do Android Keystore serão removidos. "
                                + "Será necessário configurar outro token para compilar novamente.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Remover", (confirm, which) -> {
                            try {
                                GitHubTokenStore.clear(this);
                                dialog.dismiss();
                                status.setText("Token GitHub removido deste aparelho.");
                                refresh();
                            } catch (Exception error) {
                                showError("Não foi possível remover o token", error);
                            }
                        }).show();
            });
        });
        dialog.show();
    }

    private void confirmRemoteBuild() {
        if (NativeModBuildService.isRunning(this, instanceId)) {
            status.setText(NativeModBuildService.message(this, instanceId));
            return;
        }
        final int selectedCount;
        final long selectedBytes;
        try {
            List<File> selected = HorizonNativeModManager.selectedArchiveFiles(this, instanceId);
            selectedCount = selected.size();
            long bytes = 0L;
            for (File archive : selected) bytes += archive.length();
            selectedBytes = bytes;
        } catch (Exception error) {
            showError("Não há módulos prontos para compilar", error);
            return;
        }
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
        String message = "O app enviará " + selectedCount + " módulo(s) ("
                + formatMiB(selectedBytes) + ") para uma Release temporária PÚBLICA do repositório "
                + "Ph04/MobileHorizonNative. Qualquer pessoa poderá baixar esses arquivos enquanto "
                + "o build estiver em andamento.\n\n"
                + "Depois de compilar e instalar a runtime, o app tentará apagar automaticamente a "
                + "Release e a tag. O token não é incluído nos pacotes.\n\n"
                + "A Action pode levar vários minutos e continuará em um serviço Android em primeiro "
                + "plano, com notificação de progresso. Confirme somente se você aceita a publicação "
                + "temporária dos módulos.";
        new AlertDialog.Builder(this)
                .setTitle("Enviar e compilar os .hnmod?")
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar e compilar", (dialog, which) -> {
                    try {
                        NativeModBuildService.start(this, instanceId);
                        status.setText("Compilação enviada. Acompanhe o progresso pela notificação.");
                        refreshBuildState();
                    } catch (Exception error) {
                        showError("Não foi possível iniciar a compilação", error);
                    }
                }).show();
    }

    private String formatMiB(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private void refreshBuildState() {
        if (status == null || isFinishing() || isDestroyed()) return;
        boolean running = NativeModBuildService.isRunning(this, instanceId);
        String state = NativeModBuildService.state(this, instanceId);
        String message = NativeModBuildService.message(this, instanceId);
        if ("RUNNING".equals(state) && !message.isEmpty()) {
            status.setText("Compilação remota: " + message);
        } else if ("SUCCESS".equals(state) && !message.isEmpty()) {
            status.setText(message);
        } else if ("FAILED".equals(state) && !message.isEmpty()) {
            status.setText("Falha na compilação: " + message);
        }
        if (buildButton != null) {
            buildButton.setEnabled(buildEligible && !running);
        }
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
