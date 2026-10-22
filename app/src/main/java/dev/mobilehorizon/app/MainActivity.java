package dev.mobilehorizon.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.content.pm.ApplicationInfo;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.EditText;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.mobilehorizon.compat.BackendCapability;
import dev.mobilehorizon.compat.BackendDescriptor;
import dev.mobilehorizon.compat.CompatibilityChecker;
import dev.mobilehorizon.compat.CompatibilityReport;
import dev.mobilehorizon.compat.DeviceCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_FOLDER = 101;
    private static final int REQUEST_IMPORT_ZIP = 102;
    private static final int REQUEST_EXPORT_DIAGNOSTICS = 103;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private TextView statusTitle;
    private TextView statusDetails;
    private TextView compatibilityText;
    private TextView progressDetails;
    private ProgressBar progressBar;
    private Button importFolderButton;
    private Button importZipButton;
    private Button cancelButton;
    private Button downloadBaseButton;
    private Button downloadAssetsButton;
    private Button cancelDownloadButton;
    private Button prepareInputsButton;
    private Button fullConvertButton;
    private Button openEngineButton;
    private Button smokeTestButton;
    private Button exportDiagnosticsButton;
    private Button microsoftLoginButton;
    private Button microsoftSignOutButton;
    private TextView accountStatusText;
    private TextView offlineStatusText;
    private Button offlineNameButton;
    private Button offlinePlayButton;
    private Button touchControlsButton;
    private boolean busy;
    private boolean conversionBusy;
    private boolean downloadBusy;
    private boolean receiverRegistered;
    private boolean pendingNotificationPermission;
    private boolean pendingAllAssets;
    private boolean pendingPreparationPermission;
    private boolean pendingFullPreparationPermission;
    private boolean fullConversionBusy;
    private boolean pendingAuthPermission;
    private boolean authBusy;
    private boolean launchBusy;
    private boolean diagnosticsBusy;
    private File pendingDiagnosticsArchive;
    private boolean pendingNativeSmokeResult;
    private boolean pendingNativeClientResult;
    private boolean sessionEnvSet;
    private boolean offlineNameEnvSet;
    private String lastPresentedDeviceCode;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (OfficialDownloadService.ACTION_PROGRESS.equals(intent.getAction())) {
                handleDownloadProgress(intent);
            } else if (OfficialDownloadService.ACTION_FINISHED.equals(intent.getAction())) {
                handleDownloadFinished(intent);
            } else if (ResourcePreparationService.ACTION_PROGRESS.equals(intent.getAction())) {
                handlePreparationProgress(intent);
            } else if (ResourcePreparationService.ACTION_FINISHED.equals(intent.getAction())) {
                handlePreparationFinished(intent);
            } else if (MicrosoftAuthService.ACTION_PROGRESS.equals(intent.getAction())) {
                handleMicrosoftAuthProgress(intent);
            } else if (MicrosoftAuthService.ACTION_DEVICE_CODE.equals(intent.getAction())) {
                showMicrosoftDeviceCode(intent);
            } else if (MicrosoftAuthService.ACTION_FINISHED.equals(intent.getAction())) {
                handleMicrosoftAuthFinished(intent);
            }
        }
    };
    private Uri pendingUri;
    private boolean pendingZip;
    private Future<?> currentJob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(244, 246, 251));
        getWindow().setNavigationBarColor(Color.rgb(244, 246, 251));
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        buildUi();
        updateCompatibility();
        setStatus("Pronto",
                "Baixe os arquivos oficiais do Minecraft 26.3, prepare as entradas para conversão ou importe uma saída Horizon já preparada.");
        refreshAccountUi();
        refreshOfflineUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(OfficialDownloadService.ACTION_PROGRESS);
        filter.addAction(OfficialDownloadService.ACTION_FINISHED);
        filter.addAction(ResourcePreparationService.ACTION_PROGRESS);
        filter.addAction(ResourcePreparationService.ACTION_FINISHED);
        filter.addAction(MicrosoftAuthService.ACTION_PROGRESS);
        filter.addAction(MicrosoftAuthService.ACTION_DEVICE_CODE);
        filter.addAction(MicrosoftAuthService.ACTION_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
        try {
            startService(new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_QUERY));
        } catch (Exception ignored) {
            // The query is only for restoring a progress display; it must not interrupt startup.
        }
        try {
            startService(new Intent(this, ResourcePreparationService.class)
                    .setAction(ResourcePreparationService.ACTION_QUERY));
        } catch (Exception ignored) {
            // Restoring a finished preparation result is best-effort.
        }
        try {
            startService(new Intent(this, MicrosoftAuthService.class)
                    .setAction(MicrosoftAuthService.ACTION_QUERY));
        } catch (Exception ignored) {
            // Restoring a sign-in session is best-effort.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessionEnvSet) {
            try { Os.unsetenv("HORIZON_SESSION_FILE"); } catch (Exception ignored) {}
            sessionEnvSet = false;
        }
        if (offlineNameEnvSet) {
            try { Os.unsetenv("HORIZON_PLAYER_NAME"); } catch (Exception ignored) {}
            offlineNameEnvSet = false;
        }
        boolean smokeResultExists = new File(getFilesDir(), "horizon-native-smoke-result.txt").isFile();
        boolean clientResultExists = new File(getFilesDir(), "horizon-client-result.txt").isFile();
        showNativeSmokeResultIfAvailable();
        showNativeClientResultIfAvailable();
        if (pendingNativeSmokeResult) {
            pendingNativeSmokeResult = false;
            if (!smokeResultExists) {
                new File(getFilesDir(), "horizon-native-smoke.flag").delete();
                setStatus("Teste Vulkan terminou sem relatório",
                        "A atividade nativa voltou sem gravar o resultado do teste. Isso pode indicar uma falha antes do início do código nativo ou encerramento do processo; ainda não identifica a causa. Confira os registros Android se puder.");
            }
        }
        if (pendingNativeClientResult) {
            pendingNativeClientResult = false;
            if (!clientResultExists) {
                setStatus("Cliente nativo encerrou sem relatório",
                        "A atividade do jogo voltou sem entregar um relatório de inicialização. Isso sugere que o processo nativo terminou antes de registrar o resultado; ainda não é possível atribuir a causa ao Vulkan ou aos recursos do Minecraft.");
            }
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 246, 251));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  ANDROID", 12, 0xff5363bd, true), params(-1, -2));
        TextView title = text("Minecraft no celular", 27, 0xff17213b, true);
        LinearLayout.LayoutParams titleParams = params(-1, -2);
        titleParams.topMargin = dp(6);
        page.addView(title, titleParams);
        TextView intro = text(
                "Jogue, prepare os recursos ou importe uma instalação que já está pronta.",
                14, 0xff59647b, false);
        LinearLayout.LayoutParams introParams = params(-1, -2);
        introParams.topMargin = dp(5);
        page.addView(intro, introParams);

        // Keep status and cancellation controls visible even while the detailed
        // setup sections are collapsed.
        LinearLayout statusCard = card();
        LinearLayout.LayoutParams statusCardParams = params(-1, -2);
        statusCardParams.topMargin = dp(16);
        page.addView(statusCard, statusCardParams);
        statusCard.addView(text("Estado", 13, 0xff5363bd, true), params(-1, -2));
        statusTitle = text("Pronto", 18, 0xff18213a, true);
        LinearLayout.LayoutParams statusTitleParams = params(-1, -2);
        statusTitleParams.topMargin = dp(4);
        statusCard.addView(statusTitle, statusTitleParams);
        statusDetails = text("", 13, 0xff59647b, false);
        LinearLayout.LayoutParams statusDetailsParams = params(-1, -2);
        statusDetailsParams.topMargin = dp(6);
        statusCard.addView(statusDetails, statusDetailsParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = params(-1, 8);
        progressParams.topMargin = dp(12);
        statusCard.addView(progressBar, progressParams);
        progressDetails = text("", 12, 0xff59647b, false);
        LinearLayout.LayoutParams progressTextParams = params(-1, -2);
        progressTextParams.topMargin = dp(5);
        statusCard.addView(progressDetails, progressTextParams);

        cancelButton = button("Cancelar operação", false, v -> cancelImport());
        LinearLayout.LayoutParams cancelParams = params(-1, 46);
        cancelParams.topMargin = dp(8);
        cancelButton.setVisibility(View.GONE);
        statusCard.addView(cancelButton, cancelParams);
        cancelDownloadButton = button("Cancelar download", false, v -> cancelOfficialDownload());
        LinearLayout.LayoutParams cancelDownloadParams = params(-1, 46);
        cancelDownloadParams.topMargin = dp(6);
        cancelDownloadButton.setVisibility(View.GONE);
        statusCard.addView(cancelDownloadButton, cancelDownloadParams);

        // The most common path is kept at the top instead of being buried
        // beneath download, conversion and diagnostic controls.
        LinearLayout playCard = card();
        LinearLayout.LayoutParams playCardParams = params(-1, -2);
        playCardParams.topMargin = dp(12);
        page.addView(playCard, playCardParams);
        playCard.addView(text("Jogar", 18, 0xff18213a, true), params(-1, -2));
        offlineStatusText = text("Perfil offline: " + OfflineProfile.loadName(this), 14, 0xff59647b, false);
        LinearLayout.LayoutParams offlineStatusParams = params(-1, -2);
        offlineStatusParams.topMargin = dp(5);
        playCard.addView(offlineStatusText, offlineStatusParams);
        offlinePlayButton = button("Jogar com perfil offline", true, v -> openOfflineClient());
        addSpaced(playCard, offlinePlayButton, 52, 10);
        offlineNameButton = button("Alterar nick offline", false, v -> showOfflineNameDialog());
        addSpaced(playCard, offlineNameButton, 46, 6);
        touchControlsButton = button("Customizar layout dos botões", true, v -> openTouchControlsEditor());
        addSpaced(playCard, touchControlsButton, 48, 6);
        Button resourcesManagerButton = button("Gerenciador de recursos", false,
                v -> startActivity(new Intent(this, ResourceManagerActivity.class)));
        addSpaced(playCard, resourcesManagerButton, 48, 6);
        TextView playHint = text(
                "Se for a primeira vez, prepare os recursos na seção abaixo antes de iniciar.",
                12, 0xff68728a, false);
        addSpaced(playCard, playHint, -2, 7);

        LinearLayout setup = collapsibleSection(page, "Preparar Minecraft 26.3",
                "Downloads oficiais, validação e conversão dos recursos.", true);
        setup.addView(text(
                "Baixe os arquivos oficiais. O app confere tamanho e SHA-1; baixar todos os assets é opcional e pode ocupar centenas de MB.",
                13, 0xff59647b, false), params(-1, -2));
        downloadBaseButton = button("Baixar arquivos base oficiais", true, v -> startOfficialDownload(false));
        addSpaced(setup, downloadBaseButton, 50, 10);
        downloadAssetsButton = button("Baixar base + todos os assets", false, v -> confirmFullAssetDownload());
        addSpaced(setup, downloadAssetsButton, 50, 6);

        TextView conversionTitle = text("Conversão", 15, 0xff18213a, true);
        addSpaced(setup, conversionTitle, -2, 16);
        TextView conversionNote = text(
                "A conversão completa precisa dos arquivos necessários e pode demorar. O app mostra o andamento e mantém a notificação enquanto trabalha.",
                13, 0xff59647b, false);
        addSpaced(setup, conversionNote, -2, 4);
        prepareInputsButton = button("Preparar entradas para conversão", false, v -> prepareConversionInputs());
        addSpaced(setup, prepareInputsButton, 48, 8);
        fullConvertButton = button("Converter recursos completos", true, v -> prepareFullConversion());
        addSpaced(setup, fullConvertButton, 50, 6);

        LinearLayout importSection = collapsibleSection(page, "Importar recursos já preparados",
                "Use uma pasta 26.3 ou um ZIP; não precisa de Termux.", false);
        importSection.addView(text(
                "Escolha a pasta final que contém horizon-resources.json na raiz, ou um ZIP dessa mesma pasta. O app verifica os arquivos e instala a saída no diretório privado do Horizon.",
                13, 0xff59647b, false), params(-1, -2));
        importFolderButton = button("Selecionar pasta preparada", true, v -> chooseFolder());
        addSpaced(importSection, importFolderButton, 50, 10);
        importZipButton = button("Selecionar arquivo ZIP", false, v -> chooseZip());
        addSpaced(importSection, importZipButton, 48, 6);

        LinearLayout accountSection = collapsibleSection(page, "Conta Microsoft",
                "Opcional; necessário para servidores que exigem autenticação oficial.", false);
        accountStatusText = text("Nenhuma conta verificada neste dispositivo.", 14, 0xff59647b, false);
        accountSection.addView(accountStatusText, params(-1, -2));
        TextView accountNote = text(
                "O login é feito pelo fluxo oficial de código de dispositivo. A conta offline continua disponível para ambientes que aceitam esse tipo de perfil.",
                12, 0xff68728a, false);
        addSpaced(accountSection, accountNote, -2, 5);
        microsoftLoginButton = button("Entrar com Microsoft", true, v -> startMicrosoftSignIn());
        addSpaced(accountSection, microsoftLoginButton, 50, 8);
        microsoftSignOutButton = button("Remover conta salva", false, v -> signOutMicrosoft());
        microsoftSignOutButton.setVisibility(View.GONE);
        addSpaced(accountSection, microsoftSignOutButton, 46, 6);

        LinearLayout tools = collapsibleSection(page, "Ferramentas e diagnóstico",
                "Testes gráficos, verificação do cliente e exportação de logs.", false);
        openEngineButton = button("Verificar requisitos do cliente Horizon", false, v -> openNativeClient());
        addSpaced(tools, openEngineButton, 48, 0);
        smokeTestButton = button("Testar renderer Vulkan", false, v -> openNativeSmokeTest());
        addSpaced(tools, smokeTestButton, 48, 6);

        TextView diagnosticNote = text(
                "Reproduza o problema no servidor, volte aqui e exporte o ZIP. Ele pode conter endereço do servidor, nicknames e mensagens de chat; revise antes de compartilhar. Tokens e arquivos de sessão não são incluídos.",
                12, 0xff59647b, false);
        addSpaced(tools, diagnosticNote, -2, 12);
        exportDiagnosticsButton = button("Exportar diagnóstico multiplayer", true, v -> exportDiagnostics());
        addSpaced(tools, exportDiagnosticsButton, 50, 6);

        LinearLayout compatibilityCard = card();
        LinearLayout.LayoutParams compatParams = params(-1, -2);
        compatParams.topMargin = dp(12);
        tools.addView(compatibilityCard, compatParams);
        compatibilityCard.addView(text("Compatibilidade do aparelho", 15, 0xff18213a, true), params(-1, -2));
        compatibilityText = text("", 13, 0xff59647b, false);
        LinearLayout.LayoutParams compatTextParams = params(-1, -2);
        compatTextParams.topMargin = dp(5);
        compatibilityCard.addView(compatibilityText, compatTextParams);

        TextView footer = text(
                "Dica: comece pelos arquivos base. Baixe todos os assets ou use a conversão completa só quando necessário.",
                12, 0xff68728a, false);
        LinearLayout.LayoutParams footerParams = params(-1, -2);
        footerParams.topMargin = dp(14);
        page.addView(footer, footerParams);
    }

    private LinearLayout collapsibleSection(LinearLayout parent, String title, String description, boolean expanded) {
        LinearLayout shell = card();
        LinearLayout.LayoutParams shellParams = params(-1, -2);
        shellParams.topMargin = dp(10);
        parent.addView(shell, shellParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setFocusable(true);
        header.setClickable(true);
        TextView heading = text((expanded ? "▾  " : "▸  ") + title, 16, 0xff18213a, true);
        header.addView(heading, params(-1, -2));
        TextView summary = text(description, 12, 0xff68728a, false);
        LinearLayout.LayoutParams summaryParams = params(-1, -2);
        summaryParams.topMargin = dp(4);
        header.addView(summary, summaryParams);
        shell.addView(header, params(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams bodyParams = params(-1, -2);
        bodyParams.topMargin = dp(10);
        shell.addView(body, bodyParams);
        View.OnClickListener toggle = v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            heading.setText((show ? "▾  " : "▸  ") + title);
            header.setContentDescription((show ? "Recolher " : "Expandir ") + title);
        };
        header.setOnClickListener(toggle);
        heading.setOnClickListener(toggle);
        summary.setOnClickListener(toggle);
        header.setContentDescription((expanded ? "Recolher " : "Expandir ") + title);
        return body;
    }

    private void addSpaced(LinearLayout parent, View child, int height, int topMarginDp) {
        LinearLayout.LayoutParams childParams = params(-1, height);
        childParams.topMargin = dp(topMarginDp);
        parent.addView(child, childParams);
    }

    private void updateCompatibility() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (manager != null) manager.getMemoryInfo(memory);
            DeviceCapabilities device = new DeviceCapabilities(
                    Build.VERSION.SDK_INT,
                    Arrays.asList(Build.SUPPORTED_ABIS),
                    Build.VERSION.SDK_INT >= 19,
                    manager == null ? null : memory.availMem);
            BackendDescriptor importer = new BackendDescriptor(
                    "android-saf-importer",
                    BuildConfig.VERSION_NAME,
                    Collections.emptySet(),
                    26,
                    EnumSet.of(BackendCapability.IMPORT_OUTPUT, BackendCapability.REPORT_PROGRESS),
                    null);
            CompatibilityReport report = CompatibilityChecker.INSTANCE.check(device, importer);
            compatibilityText.setText("Estado: " + report.getStatus().name()
                    + "\nAndroid API: " + Build.VERSION.SDK_INT
                    + "\nABIs reportadas: " + String.join(", ", Build.SUPPORTED_ABIS)
                    + "\n\nA verificação cobre esta etapa de importação. Ela não certifica compatibilidade gráfica nem inicialização de um motor.");
        } catch (Throwable error) {
            compatibilityText.setText("A verificação detalhada da camada de compatibilidade não foi concluída: "
                    + safeMessage(error));
        }
    }

    private void chooseFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_IMPORT_FOLDER);
    }

    private void chooseZip() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_DIAGNOSTICS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveDiagnosticArchive(data.getData());
            } else {
                discardPendingDiagnosticArchive();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        boolean zip = requestCode == REQUEST_IMPORT_ZIP;
        if (!zip && requestCode != REQUEST_IMPORT_FOLDER) return;
        pendingUri = data.getData();
        pendingZip = zip;
        try {
            getContentResolver().takePersistableUriPermission(pendingUri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (SecurityException ignored) {
            // Some document providers offer only a temporary read grant; the import runs immediately.
        }
        File active = new File(getFilesDir(), ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
        if (active.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("Substituir importação atual?")
                    .setMessage("A importação nova só será ativada depois de terminar e passar pela validação inicial. A cópia anterior será preservada se ocorrer uma falha.")
                    .setNegativeButton("Manter atual", (dialog, which) -> clearPendingImport())
                    .setPositiveButton("Substituir", (dialog, which) -> beginImport())
                    .show();
        } else {
            beginImport();
        }
    }

    private void beginImport() {
        if (busy || pendingUri == null) return;
        final Uri source = pendingUri;
        final boolean isZip = pendingZip;
        pendingUri = null;
        busy = true;
        cancelRequested.set(false);
        setBusyUi(true);
        progressBar.setIndeterminate(isZip);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        progressDetails.setText(isZip ? "Preparando leitura do arquivo ZIP…" : "Analisando a pasta selecionada…");
        setStatus("Importação em andamento", "Os dados estão sendo copiados para uma área temporária. Você pode cancelar sem substituir a importação anterior.");
        currentJob = executor.submit(() -> {
            try {
                PreparedResourceImporter.ProgressListener listener = progress ->
                        runOnUiThread(() -> updateProgress(progress, isZip));
                PreparedResourceImporter.ImportSummary summary = isZip
                        ? PreparedResourceImporter.importZip(getContentResolver(), source, getFilesDir(), cancelRequested, listener)
                        : PreparedResourceImporter.importTree(getContentResolver(), source, getFilesDir(), cancelRequested, listener);
                runOnUiThread(() -> finishImport(summary, null));
            } catch (Exception error) {
                runOnUiThread(() -> finishImport(null, error));
            }
        });
    }

    private void updateProgress(PreparedResourceImporter.Progress progress, boolean isZip) {
        if (!busy || isFinishing() || isDestroyed()) return;
        if (isZip) {
            progressBar.setIndeterminate(true);
        } else if (progress.totalBytes > 0) {
            progressBar.setIndeterminate(false);
            int value = (int) Math.min(1000, (progress.bytesProcessed * 1000L) / progress.totalBytes);
            progressBar.setProgress(value);
        } else if (progress.totalFiles > 0) {
            progressBar.setIndeterminate(false);
            int value = (int) Math.min(1000, (progress.completedFiles * 1000L) / progress.totalFiles);
            progressBar.setProgress(value);
        } else {
            progressBar.setIndeterminate(true);
        }
        String files = progress.completedFiles + (progress.totalFiles > 0 ? " / " + progress.totalFiles : "");
        String bytes = formatBytes(progress.bytesProcessed);
        if (progress.totalBytes > 0) bytes += " / " + formatBytes(progress.totalBytes);
        progressDetails.setText(progress.stage + "\nArquivos: " + files + "\nDados copiados: " + bytes);
    }

    private void finishImport(PreparedResourceImporter.ImportSummary summary, Exception error) {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        currentJob = null;
        setBusyUi(false);
        progressBar.setIndeterminate(false);
        if (error == null && summary != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(1000);
            progressDetails.setText(summary.files + " arquivos · " + formatBytes(summary.bytes));
            setStatus("Recursos Horizon importados", "Destino: .local/horizon-client/" + OfficialMinecraftInstaller.VERSION + "\n" + summary.validationNote
                    + "\n\nOs arquivos anteriores só foram substituídos depois da conclusão da importação.");
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText("");
            if (error instanceof InterruptedException || cancelRequested.get()) {
                setStatus("Importação cancelada", "A pasta temporária foi descartada. A importação anterior, se existia, não foi substituída.");
            } else {
                setStatus("Não foi possível importar", safeMessage(error)
                        + "\n\nA importação anterior, se existia, foi mantida.");
            }
        }
    }

    private void cancelImport() {
        if (authBusy) {
            try {
                startService(new Intent(this, MicrosoftAuthService.class)
                        .setAction(MicrosoftAuthService.ACTION_CANCEL));
                progressDetails.setText("Cancelamento solicitado ao login Microsoft…");
            } catch (Exception error) {
                setStatus("Falha ao solicitar cancelamento", safeMessage(error));
            }
            return;
        }
        if (!busy && !conversionBusy) return;
        if (conversionBusy && !busy) {
            cancelButton.setEnabled(false);
            progressDetails.setText("Cancelamento solicitado ao serviço. A operação vai parar em um ponto seguro…");
            try {
                startService(new Intent(this, ResourcePreparationService.class)
                        .setAction(ResourcePreparationService.ACTION_CANCEL));
            } catch (Exception error) {
                setStatus("Falha ao solicitar cancelamento", safeMessage(error));
            }
            return;
        }
        cancelRequested.set(true);
        cancelButton.setEnabled(false);
        progressDetails.setText("Cancelamento solicitado. A operação vai parar em um ponto seguro…");
    }

    private void confirmFullAssetDownload() {
        startOfficialDownload(true);
    }

    private void startOfficialDownload(boolean allAssets) {
        if (downloadBusy || busy || conversionBusy) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingNotificationPermission = true;
            pendingAllAssets = allAssets;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 26032);
            return;
        }
        confirmOfficialDownload(allAssets);
    }

    private void confirmOfficialDownload(boolean allAssets) {
        String extra = allAssets
                ? "\\n\\nEsta opção baixa milhares de objetos e pode usar centenas de MB."
                : "";
        new AlertDialog.Builder(this)
                .setTitle("Baixar arquivos oficiais do Minecraft?")
                .setMessage("Este fluxo baixa arquivos oficiais da Mojang, verifica tamanho e SHA-1, mas não autentica uma conta nem concede uma licença. Prossiga apenas se você tiver uma licença válida de Minecraft Java Edition." + extra)
                .setNegativeButton("Voltar", null)
                .setPositiveButton("Continuar", (dialog, which) -> startOfficialDownloadNow(allAssets))
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 26033 && pendingPreparationPermission) {
            pendingPreparationPermission = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startBackgroundPreparationNow();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Notificações desativadas")
                        .setMessage("A preparação pode continuar, mas o Android talvez não mostre a notificação na área de notificações. Você pode permitir isso nas configurações do app.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar mesmo assim", (dialog, which) -> startBackgroundPreparationNow())
                        .show();
            }
            return;
        }
        if (requestCode == 26035 && pendingFullPreparationPermission) {
            pendingFullPreparationPermission = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startFullConversionNow();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Notificações desativadas")
                        .setMessage("A conversão pode continuar, mas o progresso e a conclusão talvez não apareçam na área de notificações.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar sem notificações", (dialog, which) -> startFullConversionNow())
                        .show();
            }
            return;
        }
        if (requestCode == 26034 && pendingAuthPermission) {
            pendingAuthPermission = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startMicrosoftSignInNow();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Notificações desativadas")
                        .setMessage("O login pode continuar, mas o Android talvez não mostre a notificação na área de notificações.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar sem notificações", (dialog, which) -> startMicrosoftSignInNow())
                        .show();
            }
            return;
        }
        if (requestCode != 26032 || !pendingNotificationPermission) return;
        pendingNotificationPermission = false;
        boolean allAssets = pendingAllAssets;
        pendingAllAssets = false;
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted) {
            confirmOfficialDownload(allAssets);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Notificações desativadas")
                .setMessage("O download pode continuar em segundo plano, mas o progresso talvez não apareça na área de notificações. Você pode permitir notificações nas configurações do Android.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar sem notificações", (dialog, which) -> confirmOfficialDownload(allAssets))
                .show();
    }

    private void startOfficialDownloadNow(boolean allAssets) {
        if (downloadBusy || busy || conversionBusy) return;
        downloadBusy = true;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Iniciando serviço de download em segundo plano…");
        setStatus(allAssets ? "Baixando Minecraft e assets completos" : "Baixando arquivos base oficiais",
                "Destino privado: files/minecraft. Arquivos só são ativados depois da verificação SHA-1 e tamanho.");
        try {
            Intent intent = new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_START)
                    .putExtra(OfficialDownloadService.EXTRA_ALL_ASSETS, allAssets);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            downloadBusy = false;
            updateActionEnablement();
            progressBar.setVisibility(View.GONE);
            setStatus("Não foi possível iniciar o download", safeMessage(error));
        }
    }

    private void cancelOfficialDownload() {
        if (!downloadBusy) return;
        cancelDownloadButton.setEnabled(false);
        progressDetails.setText("Cancelamento solicitado. Arquivos parciais não verificados serão descartados.");
        try {
            startService(new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_CANCEL));
        } catch (Exception error) {
            setStatus("Falha ao solicitar cancelamento", safeMessage(error));
        }
    }

    private void handleDownloadProgress(Intent intent) {
        downloadBusy = true;
        updateActionEnablement();
        String stage = intent.getStringExtra("stage");
        String item = intent.getStringExtra("item");
        int completed = intent.getIntExtra("completed", 0);
        int total = intent.getIntExtra("total", -1);
        long itemBytes = intent.getLongExtra("itemBytes", 0);
        long itemTotalBytes = intent.getLongExtra("itemTotalBytes", -1);
        long completedBytes = intent.getLongExtra("completedBytes", 0);
        long totalBytes = intent.getLongExtra("totalBytes", -1);
        float fraction = -1f;
        if (total > 0) {
            fraction = Math.max(0f, Math.min(1f, (float) completed / total));
            if (itemTotalBytes > 0) {
                fraction = Math.max(fraction, Math.min(1f,
                        ((float) completed + Math.min(1f, (float) itemBytes / itemTotalBytes)) / total));
            }
        } else if (itemTotalBytes > 0) {
            fraction = Math.max(0f, Math.min(1f, (float) itemBytes / itemTotalBytes));
        }
        progressBar.setIndeterminate(fraction < 0f);
        if (fraction >= 0f) progressBar.setProgress(Math.round(fraction * 1000f));
        String fileCount = total > 0 ? completed + " / " + total : "não definido";
        String bytes = formatBytes(itemBytes);
        if (itemTotalBytes > 0) bytes += " / " + formatBytes(itemTotalBytes);
        String aggregate = formatBytes(completedBytes);
        if (totalBytes > 0) aggregate += " / " + formatBytes(totalBytes);
        progressDetails.setText((stage == null ? "Download" : stage)
                + (item == null || item.isEmpty() ? "" : "\nItem: " + item)
                + "\nContagem: " + fileCount
                + "\nArquivo atual: " + bytes
                + "\nTotal conhecido: " + aggregate);
        setStatus("Download oficial em andamento", "O serviço pode continuar mesmo se você sair desta tela. O sistema também mostra uma notificação de progresso.");
    }

    private void handleDownloadFinished(Intent intent) {
        boolean success = intent.getBooleanExtra("success", false);
        String message = intent.getStringExtra("message");
        int libraries = intent.getIntExtra("libraries", 0);
        int assets = intent.getIntExtra("assets", 0);
        String manifestSha1 = intent.getStringExtra("versionSha1");
        downloadBusy = false;
        cancelDownloadButton.setEnabled(true);
        updateActionEnablement();
        progressBar.setIndeterminate(false);
        if (success) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(1000);
            progressDetails.setText("Destino: files/minecraft\nBibliotecas: " + libraries
                    + (assets > 0 ? "\nAssets baixados: " + assets : "\nAssets completos: ainda não baixados")
                    + (manifestSha1 == null || manifestSha1.isEmpty() ? "" : "\nSHA-1 do JSON: " + manifestSha1));
            setStatus("Download oficial concluído", message == null ? "Arquivos verificados." : message);
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText("");
            setStatus("Download não concluído", message == null ? "Verifique a conexão e tente novamente." : message);
        }
        try {
            startService(new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_ACK));
        } catch (Exception ignored) {
            // The current UI already consumed the result; failure to clear persisted replay is harmless.
        }
    }

    private void prepareFullConversion() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingFullPreparationPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 26035);
            return;
        }
        startFullConversionNow();
    }

    private void startFullConversionNow() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy) return;
        conversionBusy = true;
        fullConversionBusy = true;
        cancelRequested.set(false);
        currentJob = null;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Preparando Java 25 ARM64 e iniciando o exportador completo…");
        setStatus("Conversão completa em andamento",
                "Na primeira execução serão baixados os pacotes do runtime Java 25. Use “Baixar base + todos os assets” antes de começar. A conversão pode levar bastante tempo e precisa de espaço livre.");
        try {
            Intent intent = new Intent(this, ResourcePreparationService.class)
                    .setAction(ResourcePreparationService.ACTION_START_FULL);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            conversionBusy = false;
            fullConversionBusy = false;
            updateActionEnablement();
            progressBar.setVisibility(View.GONE);
            setStatus("Não foi possível iniciar a conversão completa", safeMessage(error));
        }
    }

    private void prepareConversionInputs() {
        if (busy || conversionBusy || downloadBusy) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingPreparationPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 26033);
            return;
        }
        startBackgroundPreparationNow();
    }

    private void startBackgroundPreparationNow() {
        if (busy || conversionBusy || downloadBusy) return;
        conversionBusy = true;
        cancelRequested.set(false);
        currentJob = null;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Iniciando serviço de preparação em segundo plano…");
        setStatus("Preparação em andamento", "O processo continuará em segundo plano, com notificação de andamento e conclusão. Esta etapa ainda prepara as entradas vanilla, não os recursos derivados finais do Horizon.");
        try {
            Intent intent = new Intent(this, ResourcePreparationService.class)
                    .setAction(ResourcePreparationService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            conversionBusy = false;
            updateActionEnablement();
            progressBar.setVisibility(View.GONE);
            setStatus("Não foi possível iniciar a preparação", safeMessage(error));
        }
    }

    private void handlePreparationProgress(Intent intent) {
        conversionBusy = true;
        fullConversionBusy = intent.getBooleanExtra("fullMode", fullConversionBusy);
        updateActionEnablement();
        String stage = intent.getStringExtra("stage");
        String detail = intent.getStringExtra("detail");
        int completed = intent.getIntExtra("completed", 0);
        int total = intent.getIntExtra("total", -1);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(total <= 0);
        if (total > 0) progressBar.setProgress((int) Math.min(1000, completed * 1000L / total));
        progressDetails.setText((stage == null ? "Preparação" : stage)
                + (detail == null || detail.isEmpty() ? "" : "\n" + detail)
                + (total > 0 ? "\nEtapa: " + completed + " / " + total : ""));
        setStatus("Preparação em andamento", "Você pode sair da tela. O serviço continua no primeiro plano e envia uma notificação de progresso.");
    }

    private void handlePreparationFinished(Intent intent) {
        boolean success = intent.getBooleanExtra("success", false);
        boolean wasCancelled = intent.getBooleanExtra("cancelled", false);
        boolean fullMode = intent.getBooleanExtra("fullMode", false);
        String message = intent.getStringExtra("message");
        if (fullMode) {
            conversionBusy = false;
            fullConversionBusy = false;
            currentJob = null;
            updateActionEnablement();
            progressBar.setIndeterminate(false);
            if (success) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(1000);
                progressDetails.setText("Saída validada: .local/horizon-client/26.3");
                setStatus("Conversão Horizon completa validada",
                        (message == null ? "O serviço concluiu e validou a árvore de recursos." : message)
                                + "\n\nA validação foi feita em segundo plano. Agora teste “Iniciar com conta offline”.");
            } else {
                progressBar.setVisibility(View.GONE);
                progressDetails.setText("");
                setStatus(wasCancelled ? "Conversão completa cancelada" : "Conversão completa falhou",
                        message == null ? "Nenhum detalhe do preparador foi retornado." : message);
            }
        } else if (success) {
            String planPath = intent.getStringExtra("planPath");
            File plan = planPath == null || planPath.isEmpty()
                    ? new File(getFilesDir(), ".local/horizon-input/26.3/conversion-plan.json")
                    : new File(planPath);
            HorizonResourceConverter.InputReport report = new HorizonResourceConverter.InputReport(
                    plan,
                    intent.getIntExtra("libraries", 0),
                    intent.getIntExtra("uniqueAssets", 0),
                    intent.getIntExtra("presentAssets", 0),
                    intent.getIntExtra("missingAssets", 0),
                    "", "", "",
                    intent.getIntExtra("clientFiles", 0),
                    intent.getIntExtra("serverFiles", 0),
                    intent.getLongExtra("sourceBytes", 0),
                    intent.getIntExtra("hydratedNames", 0),
                    intent.getLongExtra("hydratedBytes", 0),
                    intent.getStringExtra("reportNote"));
            finishConversion(report, null);
        } else if (wasCancelled) {
            finishConversion(null, new InterruptedException(
                    message == null ? "Preparação cancelada." : message));
        } else {
            finishConversion(null, new IllegalStateException(
                    message == null ? "Erro desconhecido na preparação." : message));
        }
        try {
            startService(new Intent(this, ResourcePreparationService.class)
                    .setAction(ResourcePreparationService.ACTION_ACK));
        } catch (Exception ignored) {
            // The service stores the result so it can be replayed if the UI was not available.
        }
    }

    private void finishConversion(HorizonResourceConverter.InputReport report, Exception error) {
        if (isFinishing() || isDestroyed()) return;
        conversionBusy = false;
        currentJob = null;
        updateActionEnablement();
        progressBar.setIndeterminate(false);
        if (error == null && report != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(1000);
            progressDetails.setText("Plano: .local/horizon-input/26.3/conversion-plan.json"
                    + "\nExportações diretas: .local/horizon-input/26.3/direct-export"
                    + "\nBibliotecas SHA-1 verificadas: " + report.verifiedLibraries
                    + "\nAssets íntegros: " + report.presentAssetObjects + " / " + report.uniqueAssets
                    + "\nArquivos extraídos do cliente: " + report.extractedClientFiles
                    + "\nArquivos extraídos do servidor: " + report.extractedServerFiles
                    + "\nNomes de assets materializados: " + report.hydratedAssetNames
                    + "\nBytes de assets materializados: " + formatBytes(report.hydratedAssetBytes)
                    + "\nRecursos vanilla extraídos: " + formatBytes(report.extractedSourceBytes));
            setStatus("Recursos vanilla e exportações diretas preparados — oráculos pendentes", report.note
                    + "\n\nForam gerados apenas 3 mapas JSON diretos em .local/horizon-input/26.3/direct-export. Isso ainda não produz .local/horizon-client/26.3 nem torna o jogo jogável. Faltam os oráculos Java 25 e a geração de registries, gameplay, render, colisão e worldgen.");
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText("");
            if (error instanceof InterruptedException || cancelRequested.get()) {
                setStatus("Preparação cancelada", safeMessage(error));
            } else {
                setStatus("Preparação para conversão falhou", safeMessage(error));
            }
        }
    }

    private void refreshOfflineUi() {
        if (offlineStatusText != null) {
            offlineStatusText.setText("Nick offline: " + OfflineProfile.loadName(this));
        }
    }

    private void showOfflineNameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        input.setText(OfflineProfile.loadName(this));
        input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle("Nick offline")
                .setMessage("Use de 3 a 16 letras, números ou sublinhados.")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    try {
                        String name = OfflineProfile.saveName(this, input.getText().toString());
                        refreshOfflineUi();
                        setStatus("Nick offline salvo", "O cliente tentará iniciar com o nome " + name + ".");
                    } catch (Exception error) {
                        showRuntimeError("Nick inválido", safeMessage(error));
                    }
                }).show();
    }

    private interface ResourceValidationCallback {
        void onValidated(String resourceSummary);
    }

    /** Recursively checking tens of thousands of files on the UI thread can trigger an ANR. */
    private void validateResourcesBeforeLaunch(ResourceValidationCallback callback) {
        if (launchBusy || isFinishing() || isDestroyed()) return;
        launchBusy = true;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Verificando a saída Horizon em segundo plano…");
        setStatus("Verificando recursos do jogo",
                "A validação continua em segundo plano para manter a interface responsiva.");
        File resources = new File(getFilesDir(), ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
        executor.submit(() -> {
            String summary = null;
            String failure = null;
            try {
                summary = HorizonResourceConverter.validatePreparedResources(resources);
            } catch (Exception error) {
                failure = safeMessage(error);
            }
            final String validatedSummary = summary;
            final String validationFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchBusy = false;
                updateActionEnablement();
                progressBar.setIndeterminate(false);
                if (validationFailure != null) {
                    progressBar.setVisibility(View.GONE);
                    progressDetails.setText("");
                    showRuntimeError("Recursos Horizon inválidos",
                            validationFailure + "\n\nNenhum jogo foi iniciado.");
                    return;
                }
                progressBar.setVisibility(View.GONE);
                progressDetails.setText("Recursos Horizon validados.");
                callback.onValidated(validatedSummary);
            });
        });
    }

    private void openOfflineClient() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy) return;
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca Horizon ausente",
                    "O APK precisa conter libhorizon_android.so para ARM64 antes que o cliente possa ser usado.");
            return;
        }
        validateResourcesBeforeLaunch(this::launchOfflineClientAfterValidation);
    }

    private void launchOfflineClientAfterValidation(String resourceSummary) {
        try {
            String playerName = OfflineProfile.loadName(this);
            MobilePerformanceProfile.applyInitialOptions(this);
            new File(getFilesDir(), "horizon-client-result.txt").delete();
            new File(getFilesDir(), "horizon-native-smoke-result.txt").delete();
            new File(getFilesDir(), "horizon-native-smoke.flag").delete();
            pendingNativeClientResult = true;
            pendingNativeSmokeResult = false;
            try { Os.unsetenv("HORIZON_SESSION_FILE"); } catch (Exception ignored) {}
            sessionEnvSet = false;
            Os.setenv("HORIZON_PLAYER_NAME", playerName, true);
            offlineNameEnvSet = true;
            Intent intent = new Intent(this, NativeGameActivity.class);
            intent.putExtra(NativeGameActivity.EXTRA_TOUCH_CONTROLS, true);
            intent.putExtra(NativeGameActivity.EXTRA_PLAYER_NAME, playerName);
            startActivity(intent);
            setStatus("Iniciando cliente em modo offline",
                    "Perfil local: " + playerName + "\n" + resourceSummary
                            + "\n\nA primeira abertura pode ter pequenas pausas enquanto o renderer inicializa e cria caches. O perfil não autentica uma conta Microsoft nem concede licença online.");
        } catch (Exception error) {
            pendingNativeClientResult = false;
            try { Os.unsetenv("HORIZON_PLAYER_NAME"); } catch (Exception ignored) {}
            offlineNameEnvSet = false;
            showRuntimeError("Não foi possível iniciar offline",
                    safeMessage(error) + "\n\nÉ necessária uma saída Horizon completa em .local/horizon-client/26.3.");
        }
    }

    private void openNativeClient() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy) return;
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca Horizon ausente",
                    "O APK precisa conter libhorizon_android.so para ARM64 antes que o cliente possa ser usado.");
            return;
        }
        validateResourcesBeforeLaunch(this::continueNativeClientAfterValidation);
    }

    private void continueNativeClientAfterValidation(String resourceSummary) {
        if (!MicrosoftAuthentication.isValidClientId(BuildConfig.HORIZON_MSA_CLIENT_ID)) {
            showRuntimeError("Autenticação ainda não configurada",
                    resourceSummary + "\n\nPara login real, o responsável pelo projeto precisa registrar um App ID próprio da Microsoft e habilitar o fluxo de dispositivo. Builds sem HORIZON_MSA_CLIENT_ID não podem autenticar.");
            return;
        }
        String refreshToken;
        try {
            refreshToken = EncryptedRefreshTokenStore.load(this);
        } catch (Exception error) {
            showRuntimeError("Não foi possível ler a sessão salva", safeMessage(error));
            return;
        }
        if (refreshToken == null) {
            showRuntimeError("Entre com sua conta Microsoft",
                    resourceSummary + "\n\nUse o botão Entrar com Microsoft. O jogo só inicia após a confirmação oficial da conta e da licença.");
            startMicrosoftSignIn();
            return;
        }
        launchAuthenticatedClient(resourceSummary, refreshToken);
    }

    private void openTouchControlsEditor() {
        startActivity(new Intent(this, TouchControlsEditorActivity.class));
    }

    private void showTouchControlsDialog() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(NativeTouchControlsView.PREFERENCES, MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(4));

        CheckBox enabled = new CheckBox(this);
        enabled.setText("Ativar controles na tela do jogo");
        enabled.setTextColor(0xff18213a);
        enabled.setChecked(prefs.getBoolean(NativeTouchControlsView.KEY_ENABLED, true));
        panel.addView(enabled, params(-1, -2));

        CheckBox leftHanded = new CheckBox(this);
        leftHanded.setText("Layout canhoto (joystick à direita)");
        leftHanded.setTextColor(0xff18213a);
        leftHanded.setChecked(prefs.getBoolean(NativeTouchControlsView.KEY_LEFT_HANDED, false));
        panel.addView(leftHanded, params(-1, -2));

        TextView sizeLabel = text("", 14, 0xff59647b, false);
        panel.addView(sizeLabel, params(-1, -2));
        SeekBar size = new SeekBar(this);
        size.setMax(60);
        size.setProgress(Math.max(0, Math.min(60, Math.round(
                (prefs.getFloat(NativeTouchControlsView.KEY_SCALE, 1f) - 0.70f) * 100f))));
        panel.addView(size, params(-1, -2));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                sizeLabel.setText("Tamanho dos controles: " + (70 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sizeLabel.setText("Tamanho dos controles: " + (70 + size.getProgress()) + "%");

        TextView opacityLabel = text("", 14, 0xff59647b, false);
        LinearLayout.LayoutParams opacityParams = params(-1, -2);
        opacityParams.topMargin = dp(8);
        panel.addView(opacityLabel, opacityParams);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(65);
        opacity.setProgress(Math.max(0, Math.min(65, Math.round(
                prefs.getFloat(NativeTouchControlsView.KEY_OPACITY, 0.82f) * 100f - 35f))));
        panel.addView(opacity, params(-1, -2));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                opacityLabel.setText("Opacidade: " + (35 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        opacityLabel.setText("Opacidade: " + (35 + opacity.getProgress()) + "%");

        TextView sensitivityLabel = text("", 14, 0xff59647b, false);
        LinearLayout.LayoutParams sensitivityParams = params(-1, -2);
        sensitivityParams.topMargin = dp(8);
        panel.addView(sensitivityLabel, sensitivityParams);
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(150);
        sensitivity.setProgress(Math.max(0, Math.min(150, Math.round(
                (prefs.getFloat(NativeTouchControlsView.KEY_SENSITIVITY, 1f) - 0.50f) * 100f))));
        panel.addView(sensitivity, params(-1, -2));
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                sensitivityLabel.setText("Sensibilidade da câmera: " + (50 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sensitivityLabel.setText("Sensibilidade da câmera: " + (50 + sensitivity.getProgress()) + "%");

        new AlertDialog.Builder(this)
                .setTitle("Controles Touch")
                .setMessage("Joystick, câmera por arrasto e botões de ação. Há layout canhoto, tamanho, opacidade e sensibilidade ajustáveis. Durante o jogo, toque no ícone superior para ocultar ou mostrar os controles.")
                .setView(panel)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    float selectedScale = (70 + size.getProgress()) / 100f;
                    float selectedOpacity = (35 + opacity.getProgress()) / 100f;
                    float selectedSensitivity = (50 + sensitivity.getProgress()) / 100f;
                    prefs.edit()
                            .putBoolean(NativeTouchControlsView.KEY_ENABLED, enabled.isChecked())
                            .putBoolean(NativeTouchControlsView.KEY_LEFT_HANDED, leftHanded.isChecked())
                            .putFloat(NativeTouchControlsView.KEY_SCALE, selectedScale)
                            .putFloat(NativeTouchControlsView.KEY_OPACITY, selectedOpacity)
                            .putFloat(NativeTouchControlsView.KEY_SENSITIVITY, selectedSensitivity)
                            .apply();
                    setStatus("Controles Touch salvos", "Tamanho: " + Math.round(selectedScale * 100)
                            + "% · opacidade: " + Math.round(selectedOpacity * 100)
                            + "% · sensibilidade: " + Math.round(selectedSensitivity * 100)
                            + "%. Os ajustes serão aplicados na próxima abertura do jogo.");
                }).show();
    }

    private void refreshAccountUi() {
        if (accountStatusText == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("microsoft_account_state", MODE_PRIVATE);
        boolean signedIn = prefs.getBoolean("hasAccount", false);
        String name = prefs.getString("accountName", "");
        accountStatusText.setText(signedIn && !name.isEmpty()
                ? "Conta verificada: " + name + "\nA licença foi verificada durante o último login."
                : "Nenhuma conta verificada neste dispositivo.");
        if (microsoftLoginButton != null) microsoftLoginButton.setText(signedIn
                ? "Atualizar sessão Microsoft" : "Entrar com Microsoft");
        if (microsoftSignOutButton != null) microsoftSignOutButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        updateActionEnablement();
    }

    private void startMicrosoftSignIn() {
        if (authBusy || launchBusy || busy || conversionBusy || downloadBusy) return;
        if (!MicrosoftAuthentication.isValidClientId(BuildConfig.HORIZON_MSA_CLIENT_ID)) {
            showRuntimeError("App ID Microsoft não configurado",
                    "Este APK foi compilado sem HORIZON_MSA_CLIENT_ID. O mantenedor precisa criar um registro Microsoft próprio com contas pessoais e fluxo de código de dispositivo, depois solicitar aprovação de acesso às APIs do Minecraft. O ID de outro launcher não pode ser reutilizado.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingAuthPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 26034);
            return;
        }
        startMicrosoftSignInNow();
    }

    private void startMicrosoftSignInNow() {
        if (authBusy || launchBusy || busy || conversionBusy || downloadBusy) return;
        authBusy = true;
        lastPresentedDeviceCode = null;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Iniciando autenticação Microsoft…");
        setStatus("Login Microsoft em andamento",
                "O login continuará em segundo plano. O aplicativo salva somente o token de renovação criptografado no Android Keystore.");
        try {
            Intent intent = new Intent(this, MicrosoftAuthService.class).setAction(MicrosoftAuthService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            authBusy = false;
            updateActionEnablement();
            progressBar.setVisibility(View.GONE);
            setStatus("Não foi possível iniciar o login", safeMessage(error));
        }
    }

    private void handleMicrosoftAuthProgress(Intent intent) {
        authBusy = true;
        updateActionEnablement();
        String stage = intent.getStringExtra("stage");
        String detail = intent.getStringExtra("detail");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText((stage == null ? "Login Microsoft" : stage)
                + (detail == null || detail.isEmpty() ? "" : "\n" + detail));
        setStatus("Login Microsoft em andamento",
                "A autenticação e a checagem de licença estão sendo feitas pelos serviços oficiais.");
    }

    private void showMicrosoftDeviceCode(Intent intent) {
        String code = intent.getStringExtra("userCode");
        String verificationUri = intent.getStringExtra("verificationUri");
        String serviceMessage = intent.getStringExtra("message");
        if (code == null || code.isEmpty() || verificationUri == null || verificationUri.isEmpty()) return;
        if (code.equals(lastPresentedDeviceCode) || isFinishing() || isDestroyed()) return;
        lastPresentedDeviceCode = code;
        TextView instructions = text(
                "Código: " + code + "\n\n" + verificationUri
                        + "\n\nAbra a página oficial, informe o código e conclua o login. Volte aqui depois."
                        + (serviceMessage == null || serviceMessage.isEmpty() ? "" : "\n\n" + serviceMessage),
                16, 0xff24304c, false);
        instructions.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle("Entre com a Microsoft")
                .setView(instructions)
                .setPositiveButton("Abrir página", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)));
                    } catch (Exception error) {
                        showRuntimeError("Não foi possível abrir o navegador", safeMessage(error));
                    }
                })
                .setNeutralButton("Copiar código", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Código de login Microsoft", code));
                        setStatus("Código copiado", "Conclua o login na página oficial da Microsoft.");
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void handleMicrosoftAuthFinished(Intent intent) {
        authBusy = false;
        boolean success = intent.getBooleanExtra("success", false);
        boolean cancelledLogin = intent.getBooleanExtra("cancelled", false);
        String message = intent.getStringExtra("message");
        lastPresentedDeviceCode = null;
        progressBar.setIndeterminate(false);
        if (success) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(1000);
            refreshAccountUi();
            setStatus("Conta Microsoft verificada",
                    message == null ? "A conta e os direitos de Java Edition foram verificados." : message);
        } else {
            progressBar.setVisibility(View.GONE);
            setStatus(cancelledLogin ? "Login cancelado" : "Login não concluído",
                    message == null ? "A autenticação não foi concluída." : message);
            refreshAccountUi();
        }
        updateActionEnablement();
        try {
            startService(new Intent(this, MicrosoftAuthService.class).setAction(MicrosoftAuthService.ACTION_ACK));
        } catch (Exception ignored) {}
    }

    private void signOutMicrosoft() {
        if (authBusy || launchBusy) return;
        new AlertDialog.Builder(this)
                .setTitle("Remover conta salva?")
                .setMessage("O token de renovação criptografado e os dados públicos da conta serão removidos deste dispositivo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover", (dialog, which) -> {
                    try {
                        EncryptedRefreshTokenStore.clear(this);
                        getSharedPreferences("microsoft_account_state", MODE_PRIVATE).edit().clear().commit();
                        refreshAccountUi();
                        setStatus("Conta removida", "Entre novamente pela Microsoft para iniciar o cliente online.");
                    } catch (Exception error) {
                        showRuntimeError("Falha ao remover conta", safeMessage(error));
                    }
                }).show();
    }

    private void launchAuthenticatedClient(String resourceSummary, String refreshToken) {
        launchBusy = true;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Renovando sessão Microsoft e verificando a licença…");
        setStatus("Verificando sessão antes de iniciar",
                resourceSummary + "\n\nA sessão será renovada e a licença será checada novamente.");
        executor.submit(() -> {
            try {
                MicrosoftAuthentication.MinecraftAccount account = MicrosoftAuthentication.refreshAndVerify(
                        BuildConfig.HORIZON_MSA_CLIENT_ID, refreshToken,
                        (stage, detail) -> runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            progressDetails.setText(stage + "\n" + detail);
                        }));
                EncryptedRefreshTokenStore.save(this, account.refreshToken);
                runOnUiThread(() -> {
                    launchBusy = false;
                    updateActionEnablement();
                    refreshAccountUi();
                    File sessionFile = null;
                    try {
                        MobilePerformanceProfile.applyInitialOptions(this);
                        new File(getFilesDir(), "horizon-client-result.txt").delete();
                        new File(getFilesDir(), "horizon-native-smoke-result.txt").delete();
                        new File(getFilesDir(), "horizon-native-smoke.flag").delete();
                        pendingNativeClientResult = true;
                        pendingNativeSmokeResult = false;
                        sessionFile = writeOneShotSession(account);
                        Os.setenv("HORIZON_SESSION_FILE", sessionFile.getAbsolutePath(), true);
                        sessionEnvSet = true;
                        startActivity(new Intent(this, NativeGameActivity.class)
                                .putExtra(NativeGameActivity.EXTRA_TOUCH_CONTROLS, true)
                                .putExtra(NativeGameActivity.EXTRA_SESSION_FILE, sessionFile.getAbsolutePath()));
                        setStatus("Iniciando cliente Horizon",
                                "A sessão oficial foi verificada. A inicialização ainda depende de recursos derivados completos e validação real de gameplay.");
                    } catch (Exception error) {
                        pendingNativeClientResult = false;
                        try { Os.unsetenv("HORIZON_SESSION_FILE"); } catch (Exception ignored) {}
                        if (sessionFile != null && sessionFile.exists()) sessionFile.delete();
                        sessionEnvSet = false;
                        setStatus("Não foi possível iniciar o cliente", safeMessage(error));
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    launchBusy = false;
                    updateActionEnablement();
                    progressBar.setVisibility(View.GONE);
                    String message = safeMessage(error);
                    setStatus("Sessão não válida", message);
                    if (message.contains("Entre novamente")) {
                        try { EncryptedRefreshTokenStore.clear(this); } catch (Exception ignored) {}
                        getSharedPreferences("microsoft_account_state", MODE_PRIVATE).edit().clear().commit();
                        refreshAccountUi();
                    }
                });
            }
        });
    }

    private File writeOneShotSession(MicrosoftAuthentication.MinecraftAccount account) throws Exception {
        File directory = new File(getFilesDir(), "sessions");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta privada de sessões.");
        }
        Os.chmod(directory.getAbsolutePath(), 0700);
        File target = new File(directory, "session-" + UUID.randomUUID() + ".json");
        org.json.JSONObject session = new org.json.JSONObject()
                .put("name", account.name)
                .put("uuid", account.uuid)
                .put("access_token", account.minecraftAccessToken)
                .put("user_type", "msa")
                .put("xuid", account.xuid == null ? "" : account.xuid);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(session.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            target.delete();
            throw error;
        }
        Os.chmod(target.getAbsolutePath(), 0600);
        if (!target.setLastModified(System.currentTimeMillis())) {
            target.delete();
            throw new IllegalStateException("Não foi possível validar a idade da sessão para o cliente nativo.");
        }
        return target;
    }

    private void exportDiagnostics() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy || diagnosticsBusy) {
            showRuntimeError("Ação indisponível",
                    "Termine a operação atual antes de preparar o pacote de diagnóstico.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Exportar diagnóstico multiplayer?")
                .setMessage("Será criado um ZIP com latest.log, até três logs arquivados recentes, o relatório de falha mais recente e metadados do aparelho/build. O relatório não inclui arquivos de sessão ou tokens de autenticação. O log pode conter o endereço do servidor, nicknames e mensagens de chat; compartilhe somente depois de revisar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Preparar ZIP", (dialog, which) -> buildDiagnosticArchive())
                .show();
    }

    private void buildDiagnosticArchive() {
        diagnosticsBusy = true;
        updateActionEnablement();
        setStatus("Preparando diagnóstico", "Reunindo registros do cliente e compactando o relatório…");
        executor.execute(() -> {
            File archive = null;
            try {
                File directory = new File(getCacheDir(), "diagnostics");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Não foi possível criar a pasta temporária do relatório.");
                }
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                archive = new File(directory, "MobileHorizon-multiplayer-" + stamp + ".zip");

                File horizonHome = new File(getFilesDir(), "horizon");
                File logsDirectory = new File(horizonHome, "logs");
                File latestLog = new File(logsDirectory, "latest.log");
                File[] archivedLogs = logsDirectory.listFiles((dir, name) ->
                        name.matches("\\d{4}-\\d{2}-\\d{2}-\\d+\\.log\\.gz"));
                if (archivedLogs == null) archivedLogs = new File[0];
                Arrays.sort(archivedLogs, (left, right) ->
                        Long.compare(right.lastModified(), left.lastModified()));

                File crashDirectory = new File(horizonHome, "crash-reports");
                File[] crashReports = crashDirectory.listFiles((dir, name) ->
                        name.startsWith("crash-") && name.endsWith(".txt"));
                if (crashReports == null) crashReports = new File[0];
                Arrays.sort(crashReports, (left, right) ->
                        Long.compare(right.lastModified(), left.lastModified()));

                File clientResult = new File(getFilesDir(), "horizon-client-result.txt");
                File smokeResult = new File(getFilesDir(), "horizon-native-smoke-result.txt");

                String metadata = "MobileHorizon multiplayer diagnostic\n"
                        + "created=" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()) + "\n"
                        + "app_version=" + BuildConfig.VERSION_NAME + "\n"
                        + "package=" + getPackageName() + "\n"
                        + "android_release=" + Build.VERSION.RELEASE + "\n"
                        + "android_sdk=" + Build.VERSION.SDK_INT + "\n"
                        + "manufacturer=" + Build.MANUFACTURER + "\n"
                        + "model=" + Build.MODEL + "\n"
                        + "abis=" + String.join(",", Build.SUPPORTED_ABIS) + "\n"
                        + "latest_log_present=" + latestLog.isFile() + "\n"
                        + "archived_logs_found=" + archivedLogs.length + "\n"
                        + "crash_report_present=" + (crashReports.length > 0) + "\n"
                        + "client_result_present=" + clientResult.isFile() + "\n"
                        + "vulkan_smoke_result_present=" + smokeResult.isFile() + "\n"
                        + "privacy=Session JSON/token files are intentionally excluded. Log text may contain chat, nicknames and server addresses.\n";

                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive, false))) {
                    addDiagnosticText(zip, "diagnostic/metadata.txt", metadata);
                    addDiagnosticFile(zip, latestLog, "logs/latest.log");
                    for (int i = 0; i < Math.min(3, archivedLogs.length); i++) {
                        addDiagnosticFile(zip, archivedLogs[i], "logs/" + archivedLogs[i].getName());
                    }
                    if (crashReports.length > 0) {
                        addDiagnosticFile(zip, crashReports[0],
                                "crash-reports/" + crashReports[0].getName());
                    }
                    addDiagnosticFile(zip, clientResult, "diagnostic/horizon-client-result.txt");
                    addDiagnosticFile(zip, smokeResult, "diagnostic/horizon-native-smoke-result.txt");
                    if (!latestLog.isFile()) {
                        addDiagnosticText(zip, "diagnostic/LOG_MISSING.txt",
                                "logs/latest.log was not found at export time. Launch the client, reproduce the multiplayer problem, exit to the launcher, then export again.\n");
                    }
                }

                final File readyArchive = archive;
                runOnUiThread(() -> {
                    diagnosticsBusy = false;
                    pendingDiagnosticsArchive = readyArchive;
                    updateActionEnablement();
                    Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    create.addCategory(Intent.CATEGORY_OPENABLE);
                    create.setType("application/zip");
                    create.putExtra(Intent.EXTRA_TITLE, readyArchive.getName());
                    try {
                        startActivityForResult(create, REQUEST_EXPORT_DIAGNOSTICS);
                    } catch (Exception error) {
                        discardPendingDiagnosticArchive();
                        showRuntimeError("Não foi possível salvar o diagnóstico", safeMessage(error));
                    }
                });
            } catch (Exception error) {
                if (archive != null) archive.delete();
                runOnUiThread(() -> {
                    diagnosticsBusy = false;
                    updateActionEnablement();
                    showRuntimeError("Falha ao preparar o diagnóstico", safeMessage(error));
                    setStatus("Falha ao exportar logs", safeMessage(error));
                });
            }
        });
    }

    private void addDiagnosticText(ZipOutputStream zip, String path, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void addDiagnosticFile(ZipOutputStream zip, File source, String archivePath) throws IOException {
        if (source == null || !source.isFile()) return;
        zip.putNextEntry(new ZipEntry(archivePath));
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private void saveDiagnosticArchive(Uri destination) {
        File source = pendingDiagnosticsArchive;
        pendingDiagnosticsArchive = null;
        if (source == null || !source.isFile()) {
            diagnosticsBusy = false;
            updateActionEnablement();
            showRuntimeError("Relatório temporário ausente",
                    "O ZIP temporário não está mais disponível. Gere um novo diagnóstico.");
            return;
        }
        diagnosticsBusy = true;
        updateActionEnablement();
        setStatus("Salvando diagnóstico", "Copiando o ZIP para o local escolhido…");
        executor.execute(() -> {
            Exception failure = null;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("O Android não abriu o arquivo de destino.");
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
            } catch (Exception error) {
                failure = error;
            } finally {
                source.delete();
            }
            final Exception error = failure;
            runOnUiThread(() -> {
                diagnosticsBusy = false;
                updateActionEnablement();
                if (error == null) {
                    setStatus("Diagnóstico exportado", "Anexe o ZIP salvo nesta conversa para eu identificar recusas de recursos, falhas de decodificação, componentes de itens descartados e problemas com texturas de NPCs.");
                } else {
                    showRuntimeError("Falha ao salvar o diagnóstico", safeMessage(error));
                    setStatus("Falha ao salvar o diagnóstico", safeMessage(error));
                }
            });
        });
    }

    private void discardPendingDiagnosticArchive() {
        if (pendingDiagnosticsArchive != null) {
            pendingDiagnosticsArchive.delete();
            pendingDiagnosticsArchive = null;
        }
        diagnosticsBusy = false;
        updateActionEnablement();
    }

    private void showNativeClientResultIfAvailable() {
        File resultFile = new File(getFilesDir(), "horizon-client-result.txt");
        if (!resultFile.isFile()) return;
        try (FileInputStream input = new FileInputStream(resultFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 96 * 1024) {
                    throw new IllegalStateException("O relatório do cliente nativo excedeu 96 KiB.");
                }
                output.write(buffer, 0, read);
            }
            String result = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            boolean failed = result.startsWith("FAIL:");
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(failed ? 0 : 1000);
            progressDetails.setText(result);
            setStatus(failed ? "O cliente Horizon não iniciou" : "Cliente Horizon encerrado",
                    result + "\n\nEsse relatório é gerado pelo motor nativo. Um encerramento sem erro ainda não prova que um mundo foi carregado.");
            resultFile.delete();
        } catch (Exception error) {
            setStatus("Não foi possível ler o resultado do cliente", safeMessage(error));
        }
    }

    private void showNativeSmokeResultIfAvailable() {
        File resultFile = new File(getFilesDir(), "horizon-native-smoke-result.txt");
        if (!resultFile.isFile()) return;
        try (FileInputStream input = new FileInputStream(resultFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 32 * 1024) {
                    throw new IllegalStateException("O relatório do teste Vulkan excedeu 32 KiB.");
                }
                output.write(buffer, 0, read);
            }
            String result = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            boolean passed = result.startsWith("PASS:");
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(passed ? 1000 : 0);
            progressDetails.setText(result);
            setStatus(passed ? "Renderer Vulkan validado" : "Teste do renderer falhou",
                    result + "\n\nEsse resultado verifica o NativeActivity e a apresentação gráfica; não comprova carregamento de recursos ou gameplay.");
            resultFile.delete();
        } catch (Exception error) {
            setStatus("Não foi possível ler o relatório Vulkan", safeMessage(error));
        }
    }

    private void openNativeSmokeTest() {
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca nativa ausente",
                    "O teste Vulkan exige libhorizon_android.so empacotada para arm64-v8a.");
            return;
        }
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            showRuntimeError("Arquitetura não suportada", "O teste de renderer requer ARM64.");
            return;
        }
        File marker = new File(getFilesDir(), "horizon-native-smoke.flag");
        marker.delete(); // Legacy flag: smoke mode is now passed explicitly in the Intent.
        new File(getFilesDir(), "horizon-native-smoke-result.txt").delete();
        new File(getFilesDir(), "horizon-client-result.txt").delete();
        pendingNativeSmokeResult = true;
        pendingNativeClientResult = false;
        try {
            startActivity(new Intent(this, NativeGameActivity.class)
                    .putExtra(NativeGameActivity.EXTRA_TOUCH_CONTROLS, false)
                    .putExtra(NativeGameActivity.EXTRA_NATIVE_SMOKE_TEST, true));
            setStatus("Teste Vulkan iniciado", "Este teste cria uma superfície WGPU, solicita um adaptador Vulkan e tenta apresentar um frame de cor simples; não carrega os recursos do Minecraft.");
        } catch (Exception error) {
            pendingNativeSmokeResult = false;
            showRuntimeError("Não foi possível abrir NativeActivity", safeMessage(error));
        }
    }

    private boolean isHorizonNativeLibraryAvailable() {
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) return false;
        ApplicationInfo info = getApplicationInfo();
        File extracted = info.nativeLibraryDir == null ? null
                : new File(info.nativeLibraryDir, "libhorizon_android.so");
        if (extracted != null && extracted.isFile() && extracted.length() > 0) return true;
        List<String> apkPaths = new java.util.ArrayList<>();
        if (info.sourceDir != null) apkPaths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String path : info.splitSourceDirs) if (path != null) apkPaths.add(path);
        }
        for (String path : apkPaths) {
            try (ZipFile apk = new ZipFile(path)) {
                ZipEntry entry = apk.getEntry("lib/arm64-v8a/libhorizon_android.so");
                if (entry != null && entry.getSize() > 0) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void showRuntimeError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Entendi", null).show();
    }

    private void clearPendingImport() {
        pendingUri = null;
    }

    private void setBusyUi(boolean importing) {
        updateActionEnablement();
    }

    private void updateActionEnablement() {
        boolean blocked = busy || conversionBusy || downloadBusy || authBusy || launchBusy || diagnosticsBusy;
        if (importFolderButton != null) importFolderButton.setEnabled(!blocked);
        if (importZipButton != null) importZipButton.setEnabled(!blocked);
        if (downloadBaseButton != null) downloadBaseButton.setEnabled(!blocked);
        if (downloadAssetsButton != null) downloadAssetsButton.setEnabled(!blocked);
        if (prepareInputsButton != null) prepareInputsButton.setEnabled(!blocked);
        if (fullConvertButton != null) fullConvertButton.setEnabled(!blocked);
        if (openEngineButton != null) openEngineButton.setEnabled(!blocked);
        if (smokeTestButton != null) smokeTestButton.setEnabled(!blocked);
        if (exportDiagnosticsButton != null) exportDiagnosticsButton.setEnabled(!blocked);
        if (microsoftLoginButton != null) microsoftLoginButton.setEnabled(!blocked);
        if (microsoftSignOutButton != null) microsoftSignOutButton.setEnabled(!blocked
                && getSharedPreferences("microsoft_account_state", MODE_PRIVATE).getBoolean("hasAccount", false));
        if (offlineNameButton != null) offlineNameButton.setEnabled(!blocked);
        if (offlinePlayButton != null) offlinePlayButton.setEnabled(!blocked);
        if (cancelButton != null) {
            boolean cancellable = busy || conversionBusy || authBusy;
            cancelButton.setVisibility(cancellable ? View.VISIBLE : View.GONE);
            cancelButton.setEnabled(cancellable);
            cancelButton.setText(authBusy ? "Cancelar login"
                    : fullConversionBusy ? "Cancelar conversão completa"
                    : conversionBusy && !busy ? "Cancelar preparação" : "Cancelar importação");
        }
        if (cancelDownloadButton != null) {
            cancelDownloadButton.setVisibility(downloadBusy ? View.VISIBLE : View.GONE);
            cancelDownloadButton.setEnabled(downloadBusy);
        }
        float alpha = blocked ? 0.55f : 1f;
        if (importFolderButton != null) importFolderButton.setAlpha(alpha);
        if (importZipButton != null) importZipButton.setAlpha(alpha);
        if (downloadBaseButton != null) downloadBaseButton.setAlpha(alpha);
        if (downloadAssetsButton != null) downloadAssetsButton.setAlpha(alpha);
        if (prepareInputsButton != null) prepareInputsButton.setAlpha(alpha);
        if (fullConvertButton != null) fullConvertButton.setAlpha(alpha);
        if (openEngineButton != null) openEngineButton.setAlpha(alpha);
        if (smokeTestButton != null) smokeTestButton.setAlpha(alpha);
        if (exportDiagnosticsButton != null) exportDiagnosticsButton.setAlpha(alpha);
        if (microsoftLoginButton != null) microsoftLoginButton.setAlpha(alpha);
        if (microsoftSignOutButton != null) microsoftSignOutButton.setAlpha(alpha);
    }

    private void setStatus(String title, String details) {
        statusTitle.setText(title);
        statusDetails.setText(details);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xffe2e6f0);
        card.setBackground(background);
        return card;
    }

    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(primary ? 0xff4259d9 : Color.WHITE);
        if (!primary) background.setStroke(dp(1), 0xffd9dfed);
        button.setBackground(background);
        button.setTextColor(primary ? Color.WHITE : 0xff24304c);
        button.setOnClickListener(click);
        return button;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "desconhecido";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            cancelRequested.set(true);
            if (currentJob != null) currentJob.cancel(true);
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
