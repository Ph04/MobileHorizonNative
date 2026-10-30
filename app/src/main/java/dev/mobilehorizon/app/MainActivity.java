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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.ColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import android.widget.FrameLayout;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.io.RandomAccessFile;
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
    private static final int REQUEST_PICK_LAUNCHER_BACKGROUND = 104;
    private static final int BUTTON_SMALL_DP = 34;
    private static final int BUTTON_MEDIUM_DP = 42;
    private static final int BUTTON_LARGE_DP = 48;
    static final String EXTRA_QUICK_PLAY_SERVER = "dev.mobilehorizon.app.MAIN_QUICK_PLAY_SERVER";
    static final String EXTRA_QUICK_PLAY_WORLD = "dev.mobilehorizon.app.MAIN_QUICK_PLAY_WORLD";

    private String pendingQuickPlayServer;
    private String pendingQuickPlayWorld;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private TextView statusTitle;
    private TextView statusDetails;
    private TextView compatibilityText;
    private TextView progressDetails;
    private TextView runtimeUpdateText;
    private ProgressBar runtimeUpdateProgressBar;
    private TextView runtimeUpdateProgressDetails;
    private TextView runtimePcReleaseText;
    private TextView runtimeOfficialReleaseText;
    private Button runtimeUpdateButton;
    private boolean runtimeUpdateRequestBusy;
    private Button viewLogsButton;
    private FrameLayout tabContent;
    private LinearLayout[] tabPages;
    private ScrollView[] tabScrolls;
    private int activeTabIndex;
    private int lastAppearanceSignature = Integer.MIN_VALUE;
    private FrameLayout heroPanel;
    private LinearLayout mobileNavigation;
    private TextView[] mobileNavigationItems;
    private Button[] sidebarNavigationItems;
    private TextView sidebarInstanceNameText;
    private LinearLayout instanceDashboardList;
    private TextView instanceDashboardStatus;
    private LinearLayout offlineProfileDashboardList;
    private LinearLayout microsoftAccountDashboardList;
    private CheckBox runtimeAutoUpdatesCheck;
    private Typeface bodyTypeface = Typeface.DEFAULT;
    private Typeface pixelTypeface = Typeface.DEFAULT_BOLD;
    private Typeface buttonTypeface = Typeface.DEFAULT_BOLD;
    private Typeface monoTypeface = Typeface.MONOSPACE;
    private View downloadSectionView;
    private View instanceSectionView;
    private View worldsSectionView;
    private View resourcesSectionView;
    private View accountSectionView;
    private View logsSectionView;
    private View runtimeUpdateSectionView;
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
    private TextView instanceSummaryText;
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
    private boolean runtimeInstallBusy;
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
            if (HorizonRuntimeUpdateService.ACTION_PROGRESS.equals(intent.getAction())) {
                handleRuntimeUpdateProgress(intent);
            } else if (OfficialDownloadService.ACTION_PROGRESS.equals(intent.getAction())) {
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
    private final Handler runtimeUpdateHandler = new Handler(Looper.getMainLooper());
    private final Runnable runtimeUpdatePoll = new Runnable() {
        @Override public void run() {
            HorizonRuntimeUpdateService.checkInBackground(MainActivity.this, () -> {
                refreshRuntimeUpdateStatus();
                updateActionEnablement();
            });
            runtimeUpdateHandler.postDelayed(this, 5L * 60L * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LauncherNetworkSettings.initialize(this);
        getWindow().setStatusBarColor(Color.rgb(244, 246, 251));
        getWindow().setNavigationBarColor(Color.rgb(244, 246, 251));
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        buildUi();
        lastAppearanceSignature = LauncherAppearance.signature(this);
        updateCompatibility();
        setStatus("Pronto",
                "Baixe os arquivos oficiais do Minecraft 26.3, prepare as entradas para conversão ou importe uma saída Horizon já preparada.");
        refreshAccountUi();
        refreshOfflineUi();
        refreshInstanceSummary();
        updateActionEnablement();
        HorizonRuntimeUpdateService.checkAtStartup(this, () -> {
            refreshRuntimeUpdateStatus();
            updateActionEnablement();
        });
        handleQuickPlayIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleQuickPlayIntent(intent);
    }

    private void handleQuickPlayIntent(Intent intent) {
        if (intent == null) return;
        String server = intent.getStringExtra(EXTRA_QUICK_PLAY_SERVER);
        String world = intent.getStringExtra(EXTRA_QUICK_PLAY_WORLD);
        intent.removeExtra(EXTRA_QUICK_PLAY_SERVER);
        intent.removeExtra(EXTRA_QUICK_PLAY_WORLD);
        setIntent(intent);
        boolean hasServer = server != null && !server.trim().isEmpty();
        boolean hasWorld = world != null && !world.trim().isEmpty();
        if (!hasServer && !hasWorld) return;
        try {
            if (hasServer && hasWorld) {
                throw new IllegalArgumentException("Escolha um servidor ou um mundo para início direto.");
            }
            pendingQuickPlayServer = hasServer ? ServerFavoriteStore.validateAddress(server) : null;
            pendingQuickPlayWorld = null;
            if (hasWorld) {
                String safeWorld = world.trim();
                if (!GameWorldManager.safeFolder(safeWorld)) {
                    throw new IllegalArgumentException("Nome da pasta do mundo inválido.");
                }
                File saves = new File(GameInstanceManager.getSelectedInstanceHome(this), "saves")
                        .getCanonicalFile();
                File target = new File(saves, safeWorld).getCanonicalFile();
                if (!saves.equals(target.getParentFile())
                        || !new File(target, "level.dat").isFile()) {
                    throw new IllegalArgumentException("Esse mundo não existe na instância selecionada.");
                }
                pendingQuickPlayWorld = safeWorld;
            }
            if (hasServer && MicrosoftAccountStore.active(this) == null) {
                new AlertDialog.Builder(this)
                        .setTitle(UiLanguage.translate(this, "Entrar no servidor"))
                        .setMessage(UiLanguage.translate(this,
                                "Servidores online exigem uma conta Microsoft com licença Java. Você também pode tentar entrar usando o perfil offline, caso o servidor aceite esse modo."))
                        .setPositiveButton(UiLanguage.translate(this, "Entrar com Microsoft"),
                                (dialog, which) -> startMicrosoftSignIn())
                        .setNegativeButton(UiLanguage.translate(this, "Continuar offline"),
                                (dialog, which) -> openOfflineClient())
                        .setNeutralButton(UiLanguage.translate(this, "Cancelar"),
                                (dialog, which) -> clearPendingQuickPlay())
                        .show();
            } else if (hasWorld) {
                // Singleplayer worlds do not require a Microsoft session, even when one
                // is saved for multiplayer. Keep world quick-play independent of account state.
                openOfflineClient();
            } else if (MicrosoftAccountStore.active(this) != null) {
                openNativeClient();
            } else {
                openOfflineClient();
            }
        } catch (Exception error) {
            clearPendingQuickPlay();
            showRuntimeError("Não foi possível iniciar diretamente", safeMessage(error));
        }
    }

    private void clearPendingQuickPlay() {
        pendingQuickPlayServer = null;
        pendingQuickPlayWorld = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(HorizonRuntimeUpdateService.ACTION_PROGRESS);
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
        int appearanceSignature = LauncherAppearance.signature(this);
        if (appearanceSignature != lastAppearanceSignature) {
            int retainedTab = activeTabIndex;
            int[] retainedScroll = new int[tabScrolls == null ? 0 : tabScrolls.length];
            if (tabScrolls != null) {
                for (int i = 0; i < tabScrolls.length; i++) {
                    retainedScroll[i] = tabScrolls[i].getScrollY();
                }
            }
            buildUi();
            selectTab(Math.max(0, Math.min(retainedTab, tabPages.length - 1)));
            for (int i = 0; i < Math.min(retainedScroll.length, tabScrolls.length); i++) {
                final ScrollView target = tabScrolls[i];
                final int scrollY = retainedScroll[i];
                target.post(() -> target.scrollTo(0, scrollY));
            }
            lastAppearanceSignature = appearanceSignature;
            updateCompatibility();
            setStatus("Pronto",
                    "A aparência do launcher foi atualizada. As configurações do Minecraft permanecem inalteradas.");
            updateActionEnablement();
        }
        refreshRuntimeUpdateStatus();
        refreshInstanceSummary();
        refreshOfflineUi();
        refreshAccountUi();
        HorizonRuntimeUpdateService.checkInBackground(this, () -> {
            refreshRuntimeUpdateStatus();
            updateActionEnablement();
        });
        runtimeUpdateHandler.removeCallbacks(runtimeUpdatePoll);
        runtimeUpdateHandler.postDelayed(runtimeUpdatePoll, 5L * 60L * 1000L);
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
    protected void onPause() {
        runtimeUpdateHandler.removeCallbacks(runtimeUpdatePoll);
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Do not retain this Activity through a repeating Handler while it is not visible.
        // onResume schedules a fresh poll when the launcher returns to the foreground.
        runtimeUpdateHandler.removeCallbacks(runtimeUpdatePoll);
        if (receiverRegistered) {
            try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void refreshRuntimeUpdateStatus() {
        boolean active = HorizonRuntimeUpdateService.isProgressActive(this);
        renderRuntimeUpdateProgress(active, HorizonRuntimeUpdateService.lastStatus(this),
                HorizonRuntimeUpdateService.progressPhase(this),
                HorizonRuntimeUpdateService.progressBytes(this),
                HorizonRuntimeUpdateService.progressTotal(this));
        if (runtimePcReleaseText != null) {
            runtimePcReleaseText.setText(UiLanguage.translate(this, "Release de PC acompanhada: ") +
                     HorizonRuntimeUpdateService.pcReleaseLabel(this));
        }
        if (runtimeOfficialReleaseText != null) {
            runtimeOfficialReleaseText.setText(
                    HorizonRuntimeUpdateService.latestOfficialReleaseLabel(this));
        }
        refreshRuntimeActionButton();
        updateActionEnablement();
    }

    private void handleRuntimeUpdateProgress(Intent intent) {
        boolean active = intent.getBooleanExtra("active", false);
        if (!active) {
            refreshRuntimeUpdateStatus();
            return;
        }
        renderRuntimeUpdateProgress(true, intent.getStringExtra("message"),
                intent.getStringExtra("phase"), intent.getLongExtra("bytes", 0L),
                intent.getLongExtra("total", -1L));
        refreshRuntimeActionButton();
    }

    private void renderRuntimeUpdateProgress(boolean active, String message, String phase,
                                             long bytes, long total) {
        if (runtimeUpdateText != null && message != null && !message.trim().isEmpty()) {
            runtimeUpdateText.setText(UiLanguage.translate(this, message));
        }
        if (runtimeUpdateProgressBar != null) {
            runtimeUpdateProgressBar.setVisibility(active ? View.VISIBLE : View.GONE);
            boolean verifying = phase != null && phase.startsWith("verify");
            runtimeUpdateProgressBar.setIndeterminate(active && (total <= 0L || verifying));
            if (active && total > 0L && !verifying) {
                runtimeUpdateProgressBar.setProgress((int) Math.max(0L,
                        Math.min(1000L, bytes * 1000L / total)));
            }
        }
        if (runtimeUpdateProgressDetails != null) {
            runtimeUpdateProgressDetails.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) {
                boolean verifying = phase != null && phase.startsWith("verify");
                if (verifying) {
                    runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, "Verificando a integridade do arquivo baixado…"));
                } else if (total > 0L) {
                    int percent = (int) Math.max(0L, Math.min(100L, bytes * 100L / total));
                    runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, "Transferido: ") + formatBytes(bytes) + UiLanguage.translate(this, " de ") +
                             formatBytes(total) + "  •  " + percent + "%");
                } else if ("checking-official".equals(phase)) {
                    runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, "Consultando a versão oficial do Horizon…"));
                } else if ("checking-runtime".equals(phase)) {
                    runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, "Procurando a runtime Android publicada…"));
                } else if ("runtime-install".equals(phase)) {
                    runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, "Ativando a versão verificada…"));
                } else {
                    runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, "Conectando aos servidores de atualização…"));
                }
            } else {
                runtimeUpdateProgressDetails.setText(UiLanguage.translate(this, ""));
            }
        }
    }

    private void refreshRuntimeActionButton() {
        if (runtimeUpdateButton == null) return;
        boolean installed = HorizonRuntimeManager.hasActiveRuntimeMarker(this);
        boolean progressActive = HorizonRuntimeUpdateService.isProgressActive(this);
        String phase = HorizonRuntimeUpdateService.progressPhase(this);
        if (runtimeUpdateRequestBusy || progressActive) {
            boolean downloading = phase != null && (phase.contains("download")
                    || phase.contains("manifest") || phase.contains("install"));
            runtimeUpdateButton.setText(downloading
                    ? (installed ? "Atualizando runtime…" : "Baixando runtime…")
                    : "Verificando atualizações…");
        } else {
            runtimeUpdateButton.setText(UiLanguage.translate(this, installed
                    ? "Verificar atualizações" : "Baixar runtime mais recente"));
        }
    }

    private void startRuntimeUpdateAction() {
        if (runtimeUpdateRequestBusy || isFinishing() || isDestroyed()) return;
        runtimeUpdateRequestBusy = true;
        boolean installed = HorizonRuntimeManager.hasActiveRuntimeMarker(this);
        runtimeUpdateText.setText(installed
                ? "Verificando se existe uma runtime Horizon mais recente…"
                : "Baixando e verificando a runtime Android ARM64 mais recente…");
        refreshRuntimeActionButton();
        updateActionEnablement();
        HorizonRuntimeUpdateService.checkNow(this, () -> {
            runtimeUpdateRequestBusy = false;
            refreshRuntimeUpdateStatus();
            updateActionEnablement();
        });
    }

    private Typeface loadHorizonTypeface(String filename, Typeface fallback) {
        try {
            return Typeface.createFromAsset(getAssets(), "horizon-ui/" + filename);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void loadHorizonFonts() {
        bodyTypeface = loadHorizonTypeface("Geist-Regular.ttf",
                Typeface.create("sans-serif", Typeface.NORMAL));
        pixelTypeface = loadHorizonTypeface("PixelifySans-Bold.ttf",
                Typeface.create("sans-serif", Typeface.BOLD));
        buttonTypeface = loadHorizonTypeface("PixelifySans-SemiBold.ttf",
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        monoTypeface = loadHorizonTypeface("GeistMono-Regular.ttf", Typeface.MONOSPACE);
    }

    private void buildUi() {
        loadHorizonFonts();
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp >= 600;
        final int backgroundColor = LauncherAppearance.backgroundColor(this);
        getWindow().setStatusBarColor(0xff0b0a11);
        getWindow().setNavigationBarColor(0xff0b0a11);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        Drawable customBackground = LauncherAppearance.createBackgroundDrawable(this);
        if (customBackground != null) root.setBackground(customBackground);
        else root.setBackgroundColor(backgroundColor);

        tabContent = new FrameLayout(this);
        tabContent.setBackgroundColor(Color.TRANSPARENT);
        tabPages = new LinearLayout[6];
        tabScrolls = new ScrollView[6];
        for (int i = 0; i < tabPages.length; i++) {
            ScrollView tabScroll = new ScrollView(this);
            tabScroll.setFillViewport(true);
            tabScroll.setClipToPadding(false);
            tabScroll.setBackgroundColor(Color.TRANSPARENT);
            LinearLayout tabPage = new LinearLayout(this);
            tabPage.setOrientation(LinearLayout.VERTICAL);
            tabPage.setPadding(dp(landscape ? 18 : 12), dp(landscape ? 16 : 12),
                    dp(landscape ? 18 : 12), dp(24));
            tabScroll.addView(tabPage);
            tabContent.addView(tabScroll, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            tabPages[i] = tabPage;
            tabScrolls[i] = tabScroll;
            tabScroll.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }
        LinearLayout page = tabPages[0];
        addTabHeader(tabPages[1], "MOBILEHORIZON  /  INSTÂNCIAS", "Instâncias",
                "Escolha e organize seus ambientes de jogo sem sair desta aba.");
        addTabHeader(tabPages[2], "MOBILEHORIZON  /  MINECRAFT", "Minecraft",
                "Downloads oficiais, preparação dos recursos e atualizações do motor.");
        addTabHeader(tabPages[3], "MOBILEHORIZON  /  BIBLIOTECA", "Mundos e conteúdo",
                "Acesse diretamente mundos, pacotes, servidores, capturas e registros.");
        addTabHeader(tabPages[4], "MOBILEHORIZON  /  PERFIS", "Perfis e contas",
                "Troque identidades offline, contas Microsoft e cosméticos nesta aba.");
        addTabHeader(tabPages[5], "MOBILEHORIZON  /  SISTEMA", "Sistema e preferências",
                "Ajustes visuais, rede, atualizações, diagnóstico e ferramentas.");

        if (!landscape) {
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            TextView topTitle = text("HORIZON", 16, 0xffecedf3, true);
            topBar.addView(topTitle, new LinearLayout.LayoutParams(0, dp(28), 1f));
            topBar.addView(text("MINECRAFT 26.3  •  ANDROID", 10, 0xffa29cb8, true),
                    params(-2, -2));
            page.addView(topBar, params(-1, -2));
        }

        // Horizon-style hero/banner. The pixel backdrop is drawn natively, so it scales
        // cleanly and does not depend on a fixed-resolution desktop screenshot.
        heroPanel = new FrameLayout(this);
        heroPanel.setBackgroundColor(0xff0a0f2c);
        LinearLayout.LayoutParams heroParams = params(-1, landscape ? 250 : 220);
        heroParams.topMargin = dp(8);
        page.addView(heroPanel, heroParams);
        HorizonPixelBackdrop pixelBackdrop = new HorizonPixelBackdrop(this);
        heroPanel.addView(pixelBackdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout heroContent = new LinearLayout(this);
        heroContent.setOrientation(LinearLayout.VERTICAL);
        heroContent.setPadding(dp(16), dp(14), dp(16), dp(14));
        heroPanel.addView(heroContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout badgeLine = new LinearLayout(this);
        badgeLine.setOrientation(LinearLayout.HORIZONTAL);
        badgeLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView readyBadge = text("  ■  MINECRAFT 26.3  •  " + (UiLanguage.translate(this, resourcesPrepared() ? "READY" : "SETUP REQUIRED")) + "  ", 10, 0xffb8ffd5, true);
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setColor(0xdd111420);
        badgeBackground.setCornerRadius(0);
        readyBadge.setBackground(badgeBackground);
        readyBadge.setPadding(dp(7), dp(5), dp(7), dp(5));
        badgeLine.addView(readyBadge, params(-2, -2));
        badgeLine.addView(text("   VANILLA", 10, 0xffd3c1ff, true), params(-2, -2));
        Button languageButton = button(UiLanguage.switchLabel(this), false,
                v -> UiLanguage.toggle(this));
        languageButton.setTextSize(10);
        languageButton.setPadding(dp(4), 0, dp(4), 0);
        languageButton.setContentDescription(UiLanguage.switchDescription(this));
        LinearLayout.LayoutParams languageParams = params(64, 28);
        languageParams.leftMargin = dp(6);
        languageParams.height = dp(BUTTON_SMALL_DP);
        badgeLine.addView(languageButton, languageParams);
        heroContent.addView(badgeLine, params(-1, -2));

        LinearLayout heroMain = new LinearLayout(this);
        heroMain.setOrientation(LinearLayout.HORIZONTAL);
        heroMain.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams heroMainParams = params(-1, 0);
        heroMainParams.weight = 1f;
        heroMainParams.topMargin = dp(6);
        heroContent.addView(heroMain, heroMainParams);

        LinearLayout heroBrand = new LinearLayout(this);
        heroBrand.setOrientation(LinearLayout.VERTICAL);
        heroBrand.setGravity(Gravity.CENTER_VERTICAL);
        heroMain.addView(heroBrand, new LinearLayout.LayoutParams(0, -1, 1f));
        int heroTitleSize = landscape ? 42
                : (getResources().getConfiguration().screenWidthDp < 360 ? 28 : 34);
        heroBrand.addView(text("Horizon", heroTitleSize, 0xfff7f1ff, true),
                params(-1, -2));
        TextView heroDescription = text("Minecraft Java Edition", 12, 0xffe0c9f2, false);
        LinearLayout.LayoutParams heroDescriptionParams = params(-1, -2);
        heroDescriptionParams.topMargin = dp(1);
        heroBrand.addView(heroDescription, heroDescriptionParams);

        offlinePlayButton = button("▶  Jogar", true, v -> openOfflineClient());
        offlinePlayButton.setTextSize(17);
        int heroPlayWidth = landscape ? 156
                : (getResources().getConfiguration().screenWidthDp < 360 ? 110 : 124);
        LinearLayout.LayoutParams playButtonParams = params(heroPlayWidth, 52);
        playButtonParams.setMargins(dp(8), 0, 0, 0);
        heroMain.addView(offlinePlayButton, playButtonParams);

        LinearLayout heroActions = new LinearLayout(this);
        heroActions.setOrientation(LinearLayout.HORIZONTAL);
        heroActions.setGravity(Gravity.CENTER_VERTICAL);
        offlineNameButton = button("Nick: " + OfflineProfile.loadName(this), false,
                v -> showOfflineNameDialog());
        touchControlsButton = button("Controles", false, v -> openTouchControlsEditor());
        addButtonPair(heroActions, offlineNameButton, touchControlsButton, 38, 0);
        heroContent.addView(heroActions, params(-1, -2));

        TextView readyHeading = text("PRONTO PARA JOGAR", 11, 0xffc6b8ef, true);
        LinearLayout.LayoutParams readyHeadingParams = params(-1, -2);
        readyHeadingParams.topMargin = dp(15);
        tabPages[2].addView(readyHeading, readyHeadingParams);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams statusCardParams = params(-1, -2);
        statusCardParams.topMargin = dp(8);
        tabPages[2].addView(statusCard, statusCardParams);
        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        statusHeader.addView(text("Estado do Horizon", 15, 0xfff1edff, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        statusCard.addView(statusHeader, params(-1, -2));
        statusTitle = text("Pronto", 16, 0xffc3a4ff, true);
        LinearLayout.LayoutParams statusTitleParams = params(-1, -2);
        statusTitleParams.topMargin = dp(6);
        statusCard.addView(statusTitle, statusTitleParams);
        statusDetails = text("Recursos oficiais e motor nativo são gerenciados separadamente.",
                12, 0xffb5b0cd, false);
        LinearLayout.LayoutParams statusDetailsParams = params(-1, -2);
        statusDetailsParams.topMargin = dp(4);
        statusCard.addView(statusDetails, statusDetailsParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff8e63ff));
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff302a43));
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = params(-1, 6);
        progressParams.topMargin = dp(10);
        statusCard.addView(progressBar, progressParams);
        progressDetails = text("", 11, 0xffbcb6d5, false);
        LinearLayout.LayoutParams progressTextParams = params(-1, -2);
        progressTextParams.topMargin = dp(5);
        statusCard.addView(progressDetails, progressTextParams);
        cancelButton = button("Cancelar operação", false, v -> cancelImport());
        LinearLayout.LayoutParams cancelParams = params(-1, 42);
        cancelParams.topMargin = dp(8);
        cancelButton.setVisibility(View.GONE);
        statusCard.addView(cancelButton, cancelParams);
        cancelDownloadButton = button("Cancelar download", false, v -> cancelOfficialDownload());
        LinearLayout.LayoutParams cancelDownloadParams = params(-1, 42);
        cancelDownloadParams.topMargin = dp(6);
        cancelDownloadButton.setVisibility(View.GONE);
        statusCard.addView(cancelDownloadButton, cancelDownloadParams);

        runtimeUpdateSectionView = card();
        LinearLayout runtimeCard = (LinearLayout) runtimeUpdateSectionView;
        LinearLayout.LayoutParams runtimeParams = params(-1, -2);
        runtimeParams.topMargin = dp(10);
        page.addView(runtimeCard, runtimeParams);
        LinearLayout runtimeTitleRow = new LinearLayout(this);
        runtimeTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        runtimeTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        runtimeTitleRow.addView(text("Motor Horizon", 14, 0xfff1edff, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        runtimeTitleRow.addView(text("NATIVE  •  ARM64", 10, 0xffb69aff, true), params(-2, -2));
        runtimeCard.addView(runtimeTitleRow, params(-1, -2));
        runtimePcReleaseText = text("Release de PC acompanhada: "
                + HorizonRuntimeUpdateService.pcReleaseLabel(this), 12, 0xffc5aaff, true);
        LinearLayout.LayoutParams runtimePcReleaseParams = params(-1, -2);
        runtimePcReleaseParams.topMargin = dp(6);
        runtimeCard.addView(runtimePcReleaseText, runtimePcReleaseParams);
        runtimeOfficialReleaseText = text(
                HorizonRuntimeUpdateService.latestOfficialReleaseLabel(this),
                12, 0xffd1c9e8, true);
        LinearLayout.LayoutParams runtimeOfficialReleaseParams = params(-1, -2);
        runtimeOfficialReleaseParams.topMargin = dp(4);
        runtimeCard.addView(runtimeOfficialReleaseText, runtimeOfficialReleaseParams);
        runtimeUpdateText = text(HorizonRuntimeUpdateService.lastStatus(this), 12, 0xffb5b0cd, false);
        LinearLayout.LayoutParams runtimeUpdateTextParams = params(-1, -2);
        runtimeUpdateTextParams.topMargin = dp(5);
        runtimeCard.addView(runtimeUpdateText, runtimeUpdateTextParams);

        runtimeUpdateProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        runtimeUpdateProgressBar.setMax(1000);
        runtimeUpdateProgressBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(0xff8e63ff));
        runtimeUpdateProgressBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xff302a43));
        runtimeUpdateProgressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams runtimeProgressParams = params(-1, 6);
        runtimeProgressParams.topMargin = dp(8);
        runtimeCard.addView(runtimeUpdateProgressBar, runtimeProgressParams);

        runtimeUpdateProgressDetails = text("", 11, 0xffc5aaff, false);
        LinearLayout.LayoutParams runtimeProgressDetailsParams = params(-1, -2);
        runtimeProgressDetailsParams.topMargin = dp(4);
        runtimeUpdateProgressDetails.setVisibility(View.GONE);
        runtimeCard.addView(runtimeUpdateProgressDetails, runtimeProgressDetailsParams);

        runtimeUpdateButton = button(
                HorizonRuntimeManager.hasActiveRuntimeMarker(this)
                        ? "Verificar atualizações" : "Baixar runtime mais recente",
                false, v -> startRuntimeUpdateAction());
        refreshRuntimeActionButton();
        addSpaced(runtimeCard, runtimeUpdateButton, 40, 8);

        // Tabs now expose their useful controls directly, instead of one-button gateway screens.
        buildInstancesTab(tabPages[1]);
        buildLibraryTab(tabPages[3], landscape);

        downloadSectionView = collapsibleSection(tabPages[2], "Downloads e preparação",
                "Arquivos oficiais do Minecraft 26.3, assets e conversão para o Horizon.", true);
        LinearLayout setup = (LinearLayout) downloadSectionView;
        setup.addView(text(
                "Os arquivos são verificados por tamanho e SHA-1. O download completo de assets pode ocupar centenas de MB.",
                12, 0xffb9b3d1, false), params(-1, -2));
        downloadBaseButton = button("Baixar Minecraft (arquivos base)", true,
                v -> startOfficialDownload(false));
        downloadAssetsButton = button("Baixar base + todos os assets", false,
                v -> confirmFullAssetDownload());
        addSpaced(setup, downloadBaseButton, 46, 7);
        addSpaced(setup, downloadAssetsButton, 46, 12);

        TextView conversionTitle = text("Preparar recursos para jogar", 14, 0xffeee9ff, true);
        addSpaced(setup, conversionTitle, -2, 13);
        TextView conversionNote = text(
                "A conversão completa e a preparação de entradas mostram o progresso nesta tela e na notificação.",
                12, 0xffb9b3d1, false);
        addSpaced(setup, conversionNote, -2, 4);
        prepareInputsButton = button("Preparar entradas", false, v -> prepareConversionInputs());
        fullConvertButton = button("Conversão completa", true, v -> prepareFullConversion());
        addButtonPair(setup, prepareInputsButton, fullConvertButton, 46, 8);

        resourcesSectionView = collapsibleSection(tabPages[2], "Importar recursos preparados",
                "Importar, inspecionar e organizar recursos já preparados.", false);
        LinearLayout resourcesSection = (LinearLayout) resourcesSectionView;
        resourcesSection.addView(text(
                "Gerencie recursos compatíveis com o runtime instalado. A saída preparada do Minecraft fica no armazenamento privado do app.",
                12, 0xffb9b3d1, false), params(-1, -2));
        Button resourcesManagerButton = button("Abrir gerenciador de recursos", true,
                v -> startActivity(new Intent(this, ResourceManagerActivity.class)));
        addSpaced(resourcesSection, resourcesManagerButton, 44, 9);
        importFolderButton = button("Importar pasta preparada", false, v -> chooseFolder());
        importZipButton = button("Importar ZIP preparado", false, v -> chooseZip());
        addSpaced(resourcesSection, importFolderButton, 44, 6);
        addSpaced(resourcesSection, importZipButton, 44, 6);

        buildProfilesTab(tabPages[4]);
        buildSystemTab(tabPages[5]);

        TextView footer = text("MobileHorizon • Horizon engine • recursos oficiais verificados",
                10, 0xff817a9e, false);
        LinearLayout.LayoutParams footerParams = params(-1, -2);
        footerParams.topMargin = dp(16);
        page.addView(footer, footerParams);

        if (landscape) {
            root.addView(buildSidebar(), params(184, -1));
            root.addView(tabContent, new LinearLayout.LayoutParams(0, -1, 1f));
        } else {
            root.addView(tabContent, new LinearLayout.LayoutParams(-1, 0, 1f));
            root.addView(buildMobileNavigation(), params(-1, 58));
        }
        setContentView(root);
        selectTab(0);
        applyDashboardWindowInsets(root);
    }

    /** Keeps dashboard cards and bottom navigation out of Android's status/navigation bars. */
    private void applyDashboardWindowInsets(View root) {
        if (Build.VERSION.SDK_INT < 30) return;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        root.requestApplyInsets();
    }

    private void addTabHeader(LinearLayout page, String eyebrow, String title,
                              String description) {
        page.addView(text(eyebrow, 10, 0xff9e88ff, true), params(-1, -2));
        page.addView(text(title, 25, 0xfff0ecff, true), params(-1, -2));
        page.addView(text(description, 12, 0xffb9b3d1, false), params(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(0xff342d52);
        LinearLayout.LayoutParams dividerParams = params(-1, dp(1));
        dividerParams.topMargin = dp(12);
        dividerParams.bottomMargin = dp(5);
        page.addView(divider, dividerParams);
    }

    private boolean resourcesPrepared() {
        File resources = new File(getFilesDir(),
                ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
        return new File(resources, "horizon-resources.json").isFile();
    }

    private String resourceReadySummary() {
        if (resourcesPrepared()) {
            return "Preparado para Minecraft " + OfficialMinecraftInstaller.VERSION
                    + " • saída Horizon encontrada";
        }
        return "Ainda não preparado • baixe e prepare os recursos antes de jogar";
    }

    private LinearLayout addDashboardRow(LinearLayout parent, String title, String description,
                                 String actionLabel, View.OnClickListener action,
                                 boolean landscape) {
        LinearLayout rowCard = card();
        LinearLayout.LayoutParams rowParams = params(-1, -2);
        rowParams.topMargin = dp(7);
        parent.addView(rowCard, rowParams);
        rowCard.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        if (landscape) {
            rowCard.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            rowCard.addView(info, params(-1, -2));
        }
        info.addView(text(title, 13, 0xffeee9ff, true), params(-1, -2));
        TextView detail = text(description, 11, 0xffb5b0cd, false);
        detail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams detailParams = params(-1, -2);
        detailParams.topMargin = dp(4);
        info.addView(detail, detailParams);
        if ("Instâncias".equals(title)) instanceSummaryText = detail;
        Button actionButton = button(actionLabel, false, action);
        LinearLayout.LayoutParams actionParams = params(landscape ? 116 : -1, BUTTON_SMALL_DP);
        if (landscape) actionParams.setMargins(dp(10), 0, 0, 0);
        else actionParams.topMargin = dp(8);
        rowCard.addView(actionButton, actionParams);
        return rowCard;
    }

    private void buildInstancesTab(LinearLayout page) {
        LinearLayout actions = card();
        actions.addView(text("Ambientes separados", 15, 0xfff1edff, true), params(-1, -2));
        actions.addView(text("Cada instância guarda seus mundos, opções, logs e pacotes. O motor e os arquivos oficiais preparados continuam compartilhados.",
                12, 0xffb9b3d1, false), top(-1, -2, 5));
        Button create = button("Nova instância", true, v -> showCreateInstanceDialog());
        Button manage = button("Gerenciamento completo", false,
                v -> startActivity(new Intent(this, InstancesActivity.class)));
        addButtonPair(actions, create, manage, BUTTON_MEDIUM_DP, 9);
        Button refresh = button("Atualizar lista de instâncias", false, v -> {
            refreshInstanceDashboard();
            setStatus("Lista atualizada", "As instâncias disponíveis foram relidas.");
        });
        addSpaced(actions, refresh, BUTTON_SMALL_DP, 7);
        page.addView(actions, top(-1, -2, 9));
        instanceDashboardStatus = text("Carregando instâncias…", 11, 0xffaaa2c6, false);
        page.addView(instanceDashboardStatus, top(-1, -2, 10));
        instanceDashboardList = new LinearLayout(this);
        instanceDashboardList.setOrientation(LinearLayout.VERTICAL);
        page.addView(instanceDashboardList, top(-1, -2, 4));
        refreshInstanceDashboard();
    }

    private void refreshInstanceDashboard() {
        if (instanceDashboardList == null || isFinishing() || isDestroyed()) return;
        instanceDashboardList.removeAllViews();
        try {
            GameInstanceManager.Instance active = GameInstanceManager.getSelectedInstance(this);
            List<GameInstanceManager.Instance> instances = GameInstanceManager.listInstances(this);
            instanceDashboardStatus.setText(UiLanguage.translate(this,
                    "Instâncias disponíveis: ") + instances.size() + " / 12"
                    + "  •  " + UiLanguage.translate(this, "Em uso: ") + active.name);
            for (GameInstanceManager.Instance instance : instances) {
                boolean selected = active.id.equals(instance.id);
                InstanceMetadataStore.Metadata metadata;
                try { metadata = InstanceMetadataStore.get(this, instance.id); }
                catch (Exception ignored) { metadata = InstanceMetadataStore.Metadata.defaults(); }
                LinearLayout item = card();
                LinearLayout heading = new LinearLayout(this);
                heading.setOrientation(LinearLayout.HORIZONTAL);
                heading.setGravity(Gravity.CENTER_VERTICAL);
                heading.addView(text(instance.name, 15, 0xfff0ecff, true),
                        new LinearLayout.LayoutParams(0, -2, 1f));
                heading.addView(text(selected ? "EM USO"
                        : metadata.group.isEmpty()
                            ? (instance.isDefault() ? "PADRÃO" : "DISPONÍVEL")
                            : metadata.group.toUpperCase(Locale.ROOT),
                        9, selected ? 0xffb8ffd5 : 0xffaaa2c6, true), params(-2, -2));
                item.addView(heading, params(-1, -2));
                String kind = instance.isDefault()
                        ? "Pasta padrão preservada" : "Instância independente";
                item.addView(text(kind + "  •  " + UiLanguage.translate(this, "Tempo jogado: ")
                        + formatDashboardPlayedTime(metadata.totalPlayedMs),
                        11, 0xffaaa2c6, false), top(-1, -2, 5));
                Button choose = button(selected ? "Instância ativa" : "Usar esta instância",
                        selected, v -> {
                            try {
                                GameInstanceManager.setSelectedInstance(this, instance.id);
                                refreshInstanceDashboard();
                                refreshInstanceSummary();
                                setStatus("Instância selecionada", instance.name
                                        + " será usada na próxima inicialização do jogo.");
                            } catch (Exception error) {
                                showRuntimeError("Não foi possível trocar a instância", safeMessage(error));
                            }
                        });
                choose.setEnabled(!selected && !busy && !launchBusy);
                item.addView(choose, top(-1, BUTTON_SMALL_DP, 8));
                instanceDashboardList.addView(item, top(-1, -2, 8));
            }
        } catch (Exception error) {
            instanceDashboardStatus.setText(UiLanguage.translate(this,
                    "Não foi possível listar as instâncias: ") + safeMessage(error));
        }
    }

    private String formatDashboardPlayedTime(long millis) {
        long minutes = Math.max(0L, millis) / 60000L;
        return (minutes / 60L) + " h " + (minutes % 60L) + " min";
    }

    private void showCreateInstanceDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        input.setHint(UiLanguage.translate(this, "Nome da instância"));
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Nova instância"))
                .setView(input)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Criar"), (dialog, which) -> {
                    try {
                        GameInstanceManager.Instance created =
                                GameInstanceManager.createInstance(this, input.getText().toString());
                        GameInstanceManager.setSelectedInstance(this, created.id);
                        refreshInstanceDashboard();
                        refreshInstanceSummary();
                        setStatus("Instância criada", created.name + " está selecionada.");
                    } catch (Exception error) {
                        showRuntimeError("Não foi possível criar a instância", safeMessage(error));
                    }
                }).show();
    }

    private void buildLibraryTab(LinearLayout page, boolean landscape) {
        addDashboardRow(page, "Mundos e backups",
                "Criar, copiar, restaurar backups e excluir mundos da instância ativa.",
                "Mundos", v -> startActivity(new Intent(this, WorldsActivity.class)), landscape);
        addDashboardRow(page, "Data packs por mundo",
                "Importar, ativar, desativar e remover data packs do mundo selecionado.",
                "Data packs", v -> startActivity(new Intent(this, WorldDataPacksActivity.class)), landscape);
        addDashboardRow(page, "Pacotes instalados",
                "Ativar, desativar e remover resource packs e pacotes de shader importados.",
                "Pacotes", v -> startActivity(new Intent(this, InstalledPacksActivity.class)), landscape);
        addDashboardRow(page, "Gerenciador de recursos",
                "Importar arquivos locais, texturas e mods Horizon .hmod para a instância.",
                "Recursos", v -> startActivity(new Intent(this, ResourceManagerActivity.class)), landscape);
        addDashboardRow(page, "Explorar conteúdo",
                "Pesquisar conteúdo compatível no catálogo Modrinth e importar pacotes vanilla.",
                "Explorar", v -> startActivity(new Intent(this, ContentBrowserActivity.class)), landscape);
        addDashboardRow(page, "Servidores favoritos",
                "Organizar endereços e sincronizar favoritos com a lista do Minecraft.",
                "Servidores", v -> startActivity(new Intent(this, ServerFavoritesActivity.class)), landscape);
        addDashboardRow(page, "Capturas de tela",
                "Visualizar, exportar e excluir screenshots da instância ativa.",
                "Capturas", v -> startActivity(new Intent(this, ScreenshotsActivity.class)), landscape);
        addDashboardRow(page, "Console e logs",
                "Pesquisar registros do Horizon e acompanhar novas linhas do jogo.",
                "Console", v -> startActivity(new Intent(this, ConsoleActivity.class)), landscape);
    }

    private void buildProfilesTab(LinearLayout page) {
        LinearLayout offline = card();
        offline.addView(text("Perfis offline", 15, 0xfff1edff, true), params(-1, -2));
        offline.addView(text("Nicks locais para jogar sozinho ou entrar em servidores que aceitam modo offline.",
                11, 0xffb9b3d1, false), top(-1, -2, 4));
        offlineStatusText = text("Nick offline: " + OfflineProfile.loadName(this),
                12, 0xffb9b3d1, true);
        offline.addView(offlineStatusText, top(-1, -2, 5));
        offlineProfileDashboardList = new LinearLayout(this);
        offlineProfileDashboardList.setOrientation(LinearLayout.VERTICAL);
        offline.addView(offlineProfileDashboardList, top(-1, -2, 5));
        offline.addView(button("Adicionar perfil offline", false,
                v -> showCreateOfflineProfileDialog()), top(-1, BUTTON_MEDIUM_DP, 8));
        page.addView(offline, top(-1, -2, 9));

        LinearLayout online = card();
        online.addView(text("Contas Microsoft", 15, 0xfff1edff, true), params(-1, -2));
        accountStatusText = text("Nenhuma conta Microsoft verificada.", 12, 0xffb9b3d1, false);
        online.addView(accountStatusText, top(-1, -2, 4));
        microsoftAccountDashboardList = new LinearLayout(this);
        microsoftAccountDashboardList.setOrientation(LinearLayout.VERTICAL);
        online.addView(microsoftAccountDashboardList, top(-1, -2, 5));
        microsoftLoginButton = button("Entrar com Microsoft", true, v -> startMicrosoftSignIn());
        online.addView(microsoftLoginButton, top(-1, BUTTON_MEDIUM_DP, 8));
        openEngineButton = button("Jogar com conta Microsoft", false, v -> openNativeClient());
        online.addView(openEngineButton, top(-1, BUTTON_MEDIUM_DP, 6));
        microsoftSignOutButton = button("Remover conta selecionada", false, v -> signOutMicrosoft());
        microsoftSignOutButton.setVisibility(View.GONE);
        online.addView(microsoftSignOutButton, top(-1, BUTTON_MEDIUM_DP, 6));
        page.addView(online, top(-1, -2, 10));

        addDashboardRow(page, "Skins e capas",
                "Gerenciar skins Classic/Slim, pré-visualizar o personagem e selecionar capas da conta.",
                "Cosméticos", v -> startActivity(new Intent(this, SkinCapeActivity.class)), false);
        page.addView(text("A conta Microsoft é necessária para servidores em modo online e para sincronizar cosméticos oficiais. Perfis offline não recebem autenticação.",
                11, 0xffaaa2c6, false), top(-1, -2, 8));
        refreshOfflineDashboard();
        refreshMicrosoftAccountDashboard();
    }

    private void refreshOfflineDashboard() {
        if (offlineProfileDashboardList == null) return;
        offlineProfileDashboardList.removeAllViews();
        String selected = OfflineProfile.loadName(this);
        for (String name : OfflineProfile.listNames(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(name + (name.equalsIgnoreCase(selected) ? "  •  Em uso" : ""),
                    13, 0xffeee9ff, true), new LinearLayout.LayoutParams(0, dp(BUTTON_MEDIUM_DP), 1f));
            Button choose = button(name.equalsIgnoreCase(selected) ? "Em uso" : "Selecionar",
                    name.equalsIgnoreCase(selected), v -> {
                        try {
                            OfflineProfile.selectProfile(this, name);
                            refreshOfflineUi();
                        } catch (Exception error) {
                            showRuntimeError("Não foi possível selecionar o perfil", safeMessage(error));
                        }
                    });
            choose.setEnabled(!name.equalsIgnoreCase(selected));
            row.addView(choose, new LinearLayout.LayoutParams(dp(104), dp(BUTTON_SMALL_DP)));
            offlineProfileDashboardList.addView(row, top(-1, -2, 4));
        }
    }

    private void showCreateOfflineProfileDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        input.setHint(UiLanguage.translate(this, "Nick (3–16 caracteres)"));
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Adicionar perfil offline"))
                .setView(input)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Criar e selecionar"), (dialog, which) -> {
                    try {
                        String name = OfflineProfile.createProfile(this, input.getText().toString());
                        OfflineProfile.selectProfile(this, name);
                        refreshOfflineUi();
                    } catch (Exception error) {
                        showRuntimeError("Nick offline inválido", safeMessage(error));
                    }
                }).show();
    }

    private void refreshMicrosoftAccountDashboard() {
        if (microsoftAccountDashboardList == null) return;
        microsoftAccountDashboardList.removeAllViews();
        List<MicrosoftAccountStore.Account> accounts = MicrosoftAccountStore.list(this);
        MicrosoftAccountStore.Account active = MicrosoftAccountStore.active(this);
        if (accounts.isEmpty()) {
            microsoftAccountDashboardList.addView(text("Nenhuma conta Microsoft salva.", 11,
                    0xffaaa2c6, false), params(-1, -2));
            return;
        }
        for (MicrosoftAccountStore.Account account : accounts) {
            boolean selected = active != null && active.uuid.equalsIgnoreCase(account.uuid);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(account.name + (selected ? "  •  Em uso" : ""),
                    12, 0xffeee9ff, true), new LinearLayout.LayoutParams(0, dp(BUTTON_MEDIUM_DP), 1f));
            Button choose = button(selected ? "Ativa" : "Usar", selected, v -> {
                try {
                    MicrosoftAccountStore.select(this, account.uuid);
                    refreshAccountUi();
                } catch (Exception error) {
                    showRuntimeError("Não foi possível alternar a conta", safeMessage(error));
                }
            });
            choose.setEnabled(!selected);
            row.addView(choose, new LinearLayout.LayoutParams(dp(72), dp(BUTTON_SMALL_DP)));
            Button remove = button("×", false, v -> confirmRemoveMicrosoftAccount(account));
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(42), dp(BUTTON_SMALL_DP));
            removeParams.leftMargin = dp(5);
            row.addView(remove, removeParams);
            microsoftAccountDashboardList.addView(row, top(-1, -2, 4));
        }
    }

    private void confirmRemoveMicrosoftAccount(MicrosoftAccountStore.Account account) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Remover conta Microsoft?"))
                .setMessage(UiLanguage.translate(this,
                        "O token criptografado dessa conta será removido. As outras contas e perfis offline serão preservados."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Remover"), (dialog, which) -> {
                    try {
                        EncryptedRefreshTokenStore.clear(this, account.uuid);
                        MicrosoftAccountStore.remove(this, account.uuid);
                        refreshAccountUi();
                    } catch (Exception error) {
                        showRuntimeError("Não foi possível remover a conta", safeMessage(error));
                    }
                }).show();
    }

    private void buildSystemTab(LinearLayout page) {
        LinearLayout preferences = card();
        preferences.addView(text("Preferências do launcher", 15, 0xfff1edff, true), params(-1, -2));
        preferences.addView(text("Estas opções alteram apenas a interface e os serviços do MobileHorizon, não as configurações internas do Minecraft.",
                11, 0xffb9b3d1, false), top(-1, -2, 4));
        preferences.addView(button("Alternar português / inglês", false, v -> {
            UiLanguage.toggle(this);
            rebuildLauncherUi();
        }), top(-1, BUTTON_MEDIUM_DP, 8));
        preferences.addView(button("Cor de destaque", false, v -> showLauncherAccentPicker()),
                top(-1, BUTTON_MEDIUM_DP, 6));
        preferences.addView(button("Imagem de fundo", false, v -> chooseLauncherBackground()),
                top(-1, BUTTON_MEDIUM_DP, 6));
        preferences.addView(button("Restaurar fundo padrão", false, v -> {
            LauncherAppearance.clearBackground(this);
            rebuildLauncherUi();
        }), top(-1, BUTTON_MEDIUM_DP, 6));
        TextView panelOpacityLabel = text("Opacidade dos painéis "
                + Math.round(LauncherAppearance.panelOpacity(this) * 100f) + "%",
                12, 0xffb9b3d1, true);
        preferences.addView(panelOpacityLabel, top(-1, -2, 10));
        SeekBar panelOpacityBar = new SeekBar(this);
        panelOpacityBar.setMax(75);
        panelOpacityBar.setProgress(Math.round(LauncherAppearance.panelOpacity(this) * 100f) - 25);
        panelOpacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                panelOpacityLabel.setText(UiLanguage.translate(MainActivity.this,
                        "Opacidade dos painéis ") + (progress + 25) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                LauncherAppearance.setPanelOpacity(MainActivity.this,
                        (bar.getProgress() + 25) / 100f);
            }
        });
        preferences.addView(panelOpacityBar, top(-1, BUTTON_SMALL_DP, 2));
        page.addView(preferences, top(-1, -2, 9));

        LinearLayout network = card();
        network.addView(text("Rede e proxy", 15, 0xfff1edff, true), params(-1, -2));
        String proxy = LauncherNetworkSettings.configuredProxy(this);
        network.addView(text(proxy.isEmpty() ? "Proxy HTTP desativado" : "Proxy HTTP: " + proxy,
                11, 0xffaaa2c6, false), top(-1, -2, 4));
        network.addView(button("Configurar proxy HTTP", false, v -> showLauncherProxyDialog()),
                top(-1, BUTTON_MEDIUM_DP, 8));
        network.addView(button("Desativar proxy", false, v -> {
            try {
                LauncherNetworkSettings.setProxy(this, "");
                rebuildLauncherUi();
            } catch (Exception error) {
                showRuntimeError("Não foi possível alterar o proxy", safeMessage(error));
            }
        }), top(-1, BUTTON_MEDIUM_DP, 6));
        page.addView(network, top(-1, -2, 9));

        LinearLayout runtime = card();
        runtime.addView(text("Atualizações da runtime", 15, 0xfff1edff, true), params(-1, -2));
        runtimeAutoUpdatesCheck = new CheckBox(this);
        runtimeAutoUpdatesCheck.setText(UiLanguage.translate(this,
                "Verificar atualizações do Horizon automaticamente"));
        runtimeAutoUpdatesCheck.setTextColor(0xffeee9ff);
        runtimeAutoUpdatesCheck.setChecked(HorizonRuntimeUpdateService.isEnabled(this));
        runtimeAutoUpdatesCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff9e88ff));
        runtimeAutoUpdatesCheck.setOnCheckedChangeListener((view, checked) ->
                HorizonRuntimeUpdateService.setEnabled(this, checked));
        runtime.addView(runtimeAutoUpdatesCheck, top(-1, BUTTON_LARGE_DP, 6));
        runtime.addView(text("A instalação inicial da runtime continua obrigatória. Atualizações do motor são separadas do APK.",
                11, 0xffaaa2c6, false), top(-1, -2, 3));
        page.addView(runtime, top(-1, -2, 9));

        logsSectionView = card();
        LinearLayout tools = (LinearLayout) logsSectionView;
        tools.addView(text("Diagnóstico e ferramentas", 15, 0xfff1edff, true), params(-1, -2));
        smokeTestButton = button("Testar Vulkan", false, v -> openNativeSmokeTest());
        addSpaced(tools, smokeTestButton, BUTTON_MEDIUM_DP, 8);
        viewLogsButton = button("Abrir logs do Horizon", false, v -> showLogsDialog());
        addSpaced(tools, viewLogsButton, BUTTON_MEDIUM_DP, 6);
        addSpaced(tools, text("Os logs podem conter endereços de servidores, nicknames e mensagens. Revise o arquivo antes de compartilhar.",
                11, 0xffb9b3d1, false), -2, 7);
        exportDiagnosticsButton = button("Exportar diagnóstico", true, v -> exportDiagnostics());
        addSpaced(tools, exportDiagnosticsButton, BUTTON_MEDIUM_DP, 6);
        page.addView(tools, top(-1, -2, 9));

        LinearLayout compatibility = card();
        compatibility.addView(text("Compatibilidade do aparelho", 14, 0xfff1edff, true), params(-1, -2));
        compatibilityText = text("", 12, 0xffb9b3d1, false);
        compatibility.addView(compatibilityText, top(-1, -2, 5));
        page.addView(compatibility, top(-1, -2, 9));
        page.addView(text("Versão instalada: " + launcherVersionName(), 10, 0xff817a9e, false),
                top(-1, -2, 8));
    }

    private void rebuildLauncherUi() {
        int selected = activeTabIndex;
        buildUi();
        selectTab(Math.max(0, Math.min(selected, tabPages.length - 1)));
        lastAppearanceSignature = LauncherAppearance.signature(this);
        updateCompatibility();
        refreshAccountUi();
        refreshOfflineUi();
        refreshInstanceSummary();
        refreshRuntimeUpdateStatus();
        updateActionEnablement();
    }

    private String launcherVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "desconhecida" : info.versionName;
        } catch (Exception ignored) {
            return "desconhecida";
        }
    }

    private void showLauncherAccentPicker() {
        String[] names = LauncherAppearance.accentNames();
        for (int i = 0; i < names.length; i++) names[i] = UiLanguage.translate(this, names[i]);
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Cor de destaque"))
                .setItems(names, (dialog, which) -> {
                    try {
                        LauncherAppearance.setAccent(this, which);
                        rebuildLauncherUi();
                    } catch (Exception error) {
                        showRuntimeError("Não foi possível alterar a cor", safeMessage(error));
                    }
                }).show();
    }

    private void chooseLauncherBackground() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_PICK_LAUNCHER_BACKGROUND); }
        catch (Exception error) {
            showRuntimeError("Não foi possível abrir as imagens", safeMessage(error));
        }
    }

    private void showLauncherProxyDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://127.0.0.1:7890");
        input.setText(LauncherNetworkSettings.configuredProxy(this));
        input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Proxy HTTP"))
                .setMessage(UiLanguage.translate(this,
                        "Use http://host:porta. Deixe vazio para conexão direta."))
                .setView(input)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Salvar"), (dialog, which) -> {
                    try {
                        LauncherNetworkSettings.setProxy(this, input.getText().toString());
                        rebuildLauncherUi();
                    } catch (Exception error) {
                        showRuntimeError("Proxy inválido", safeMessage(error));
                    }
                }).show();
    }

    private LinearLayout buildSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(10), dp(14), dp(10), dp(14));
        sidebar.setBackgroundColor(0xff0b0a11);
        sidebar.addView(text("AMBIENTE DO JOGO", 10, 0xff8b82aa, true), params(-1, -2));

        LinearLayout instance = new LinearLayout(this);
        instance.setOrientation(LinearLayout.HORIZONTAL);
        instance.setGravity(Gravity.CENTER_VERTICAL);
        instance.setPadding(dp(9), dp(9), dp(7), dp(9));
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(0xff221e34);
        selected.setStroke(dp(2), 0xff05040a);
        selected.setCornerRadius(0);
        instance.setBackground(selected);
        instance.setClickable(true);
        instance.setOnClickListener(v -> selectTab(0));
        LinearLayout.LayoutParams instanceParams = params(-1, -2);
        instanceParams.topMargin = dp(10);
        sidebar.addView(instance, instanceParams);
        View grass = new View(this);
        grass.setBackgroundColor(0xff83a956);
        instance.addView(grass, params(24, 24));
        LinearLayout instanceText = new LinearLayout(this);
        instanceText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams instanceTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        instanceTextParams.setMargins(dp(7), 0, 0, 0);
        instance.addView(instanceText, instanceTextParams);
        instanceText.addView(text("Horizon", 12, 0xfff0ecff, true), params(-1, -2));
        instanceText.addView(text("Minecraft Java • 26.3", 9, 0xffb8a9d8, false), params(-1, -2));
        sidebarInstanceNameText = text("Perfil: " + currentInstanceName(), 9, 0xffb8a9d8, false);
        instanceText.addView(sidebarInstanceNameText, params(-1, -2));

        TextView navigationTitle = text("NAVEGAÇÃO", 10, 0xff8b82aa, true);
        LinearLayout.LayoutParams navTitleParams = params(-1, -2);
        navTitleParams.topMargin = dp(24);
        sidebar.addView(navigationTitle, navTitleParams);
        String[] labels = {"Início", "Inst.", "Minecraft", "Biblioteca", "Perfis", "Sistema"};
        sidebarNavigationItems = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            Button item = button(labels[i], false, v -> selectTab(tab));
            addSpaced(sidebar, item, 42, i == 0 ? 8 : 5);
            sidebarNavigationItems[i] = item;
        }

        View spacer = new View(this);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        sidebar.addView(text("ANDROID  •  ARM64", 9, 0xff817a9e, true), params(-1, -2));
        return sidebar;
    }

    private LinearLayout buildMobileNavigation() {
        mobileNavigation = new LinearLayout(this);
        mobileNavigation.setOrientation(LinearLayout.VERTICAL);
        mobileNavigation.setBackgroundColor(0xff0b0a11);

        View rule = new View(this);
        rule.setBackgroundColor(0xff342d52);
        mobileNavigation.addView(rule, params(-1, 1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        mobileNavigation.addView(row, params(-1, 57));

        String[] labels = {"Início", "Inst.", "MC", "Bibli.", "Perfis", "Sistema"};
        String[] descriptions = {"Início", "Instâncias", "Minecraft e downloads",
                "Mundos e conteúdo", "Perfis e contas", "Sistema e preferências"};
        mobileNavigationItems = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            TextView item = text(labels[i], 8, 0xffa29cb8, true);
            item.setTypeface(buttonTypeface);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setPadding(dp(1), 0, dp(1), 0);
            item.setFocusable(true);
            item.setClickable(true);
            item.setContentDescription(UiLanguage.translate(this, descriptions[i]));
            item.setOnClickListener(v -> selectTab(tab));
            row.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
            mobileNavigationItems[i] = item;
        }
        return mobileNavigation;
    }

    private void selectTab(int index) {
        if (tabScrolls == null || index < 0 || index >= tabScrolls.length) return;
        activeTabIndex = index;
        for (int i = 0; i < tabScrolls.length; i++) {
            tabScrolls[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        updateNavigationSelection(index);
    }

    private void updateNavigationSelection(int selectedIndex) {
        if (mobileNavigationItems != null) {
            for (int i = 0; i < mobileNavigationItems.length; i++) {
                TextView item = mobileNavigationItems[i];
                boolean active = selectedIndex == i;
                if (active) {
                    item.setBackground(new HorizonBlockDrawable(0xff221e34, 0xff3d3560,
                            0xff07060b, 0xff05040a, dp(1), dp(1), dp(2)));
                    item.setTextColor(0xffecedf3);
                } else {
                    item.setBackgroundColor(0xff0b0a11);
                    item.setTextColor(0xffa29cb8);
                }
            }
        }
        if (sidebarNavigationItems != null) {
            for (int i = 0; i < sidebarNavigationItems.length; i++) {
                Button item = sidebarNavigationItems[i];
                boolean active = selectedIndex == i;
                item.setBackground(new HorizonBlockDrawable(
                        active ? 0xff29243e : 0xff151321,
                        active ? 0xff7561c7 : 0xff3d3560,
                        0xff07060b, 0xff05040a, dp(1), dp(1), dp(2)));
                item.setTextColor(active ? 0xfff0ecff : 0xffb7b0cc);
            }
        }
    }

    private String currentInstanceName() {
        try {
            return GameInstanceManager.getSelectedInstance(this).name;
        } catch (Exception error) {
            return "Padrão";
        }
    }

    private void refreshInstanceSummary() {
        try {
            GameInstanceManager.Instance active = GameInstanceManager.getSelectedInstance(this);
            if (instanceSummaryText != null) {
                instanceSummaryText.setText(UiLanguage.translate(this, "Instância ativa: ")
                        + active.name + "\n"
                        + UiLanguage.translate(this, "Mundos, opções e pacotes ficam separados neste perfil."));
            }
            if (sidebarInstanceNameText != null) {
                sidebarInstanceNameText.setText(UiLanguage.translate(this, "Perfil: ") + active.name);
            }
            refreshInstanceDashboard();
        } catch (Exception error) {
            if (instanceSummaryText != null) instanceSummaryText.setText(
                    UiLanguage.translate(this, "Não foi possível ler as instâncias: ") + safeMessage(error));
        }
    }

    private int normalizedButtonHeight(int requestedDp) {
        if (requestedDp <= 0) return requestedDp;
        if (requestedDp <= 38) return BUTTON_SMALL_DP;
        if (requestedDp <= 46) return BUTTON_MEDIUM_DP;
        return BUTTON_LARGE_DP;
    }

    private void addButtonPair(LinearLayout parent, View left, View right, int heightDp, int topMarginDp) {
        int standardizedHeight = normalizedButtonHeight(heightDp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = params(-1, -2);
        rowParams.topMargin = dp(topMarginDp);
        parent.addView(row, rowParams);

        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, dp(standardizedHeight), 1f);
        leftParams.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(standardizedHeight), 1f);
        rightParams.setMargins(dp(4), 0, 0, 0);
        row.addView(left, leftParams);
        row.addView(right, rightParams);
    }

    private LinearLayout collapsibleSection(LinearLayout parent, String title, String description, boolean expanded) {
        final String localizedTitle = UiLanguage.translate(this, title);
        final String localizedDescription = UiLanguage.translate(this, description);
        LinearLayout shell = card();
        LinearLayout.LayoutParams shellParams = params(-1, -2);
        shellParams.topMargin = dp(10);
        parent.addView(shell, shellParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setFocusable(true);
        header.setClickable(true);
        TextView heading = text((expanded ? "▾  " : "▸  ") + localizedTitle, 16, 0xfff1edff, true);
        header.addView(heading, params(-1, -2));
        TextView summary = text(localizedDescription, 12, 0xffa9a3c7, false);
        LinearLayout.LayoutParams summaryParams = params(-1, -2);
        summaryParams.topMargin = dp(4);
        header.addView(summary, summaryParams);
        shell.addView(header, params(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        body.setTag(shell);
        LinearLayout.LayoutParams bodyParams = params(-1, -2);
        bodyParams.topMargin = dp(10);
        shell.addView(body, bodyParams);
        View.OnClickListener toggle = v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            heading.setText((show ? "▾  " : "▸  ") + localizedTitle);
            header.setContentDescription(UiLanguage.translate(this,
                    (show ? "Recolher " : "Expandir ") + localizedTitle));
        };
        header.setOnClickListener(toggle);
        heading.setOnClickListener(toggle);
        summary.setOnClickListener(toggle);
        header.setContentDescription(UiLanguage.translate(this,
                (expanded ? "Recolher " : "Expandir ") + localizedTitle));
        return body;
    }

    private void addSpaced(LinearLayout parent, View child, int height, int topMarginDp) {
        int actualHeight = child instanceof Button && height > 0
                ? normalizedButtonHeight(height) : height;
        LinearLayout.LayoutParams childParams = params(-1, height);
        if (child instanceof Button && actualHeight > 0) childParams.height = dp(actualHeight);
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
            compatibilityText.setText(UiLanguage.translate(this, "Estado: ") + report.getStatus().name()
                    + "\nAndroid API: " + Build.VERSION.SDK_INT
                    + "\nABIs reportadas: " + String.join(", ", Build.SUPPORTED_ABIS)
                    + "\n\nA verificação cobre esta etapa de importação. Ela não certifica compatibilidade gráfica nem inicialização de um motor.");
        } catch (Throwable error) {
            compatibilityText.setText(UiLanguage.translate(this, "A verificação detalhada da camada de compatibilidade não foi concluída: ") +
                     safeMessage(error));
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
        if (requestCode == REQUEST_PICK_LAUNCHER_BACKGROUND) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri background = data.getData();
                try { getContentResolver().takePersistableUriPermission(background,
                        data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { }
                executor.execute(() -> {
                    Exception failure = null;
                    try { LauncherAppearance.importBackground(this, background); }
                    catch (Exception error) { failure = error; }
                    final Exception result = failure;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (result != null) showRuntimeError("Imagem inválida", safeMessage(result));
                        else rebuildLauncherUi();
                    });
                });
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
                    .setTitle(UiLanguage.translate(this, "Substituir importação atual?"))
                    .setMessage(UiLanguage.translate(this, "A importação nova só será ativada depois de terminar e passar pela validação inicial. A cópia anterior será preservada se ocorrer uma falha."))
                    .setNegativeButton(UiLanguage.translate(this, "Manter atual"), (dialog, which) -> clearPendingImport())
                    .setPositiveButton(UiLanguage.translate(this, "Substituir"), (dialog, which) -> beginImport())
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
        progressDetails.setText(UiLanguage.translate(this,
                isZip ? "Preparando leitura do arquivo ZIP…" : "Analisando a pasta selecionada…"));
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
        progressDetails.setText(UiLanguage.translate(this, progress.stage)
                + "\n" + UiLanguage.translate(this, "Arquivos: ") + files
                + "\n" + UiLanguage.translate(this, "Dados copiados: ") + bytes);
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
            progressDetails.setText(UiLanguage.translate(this, ""));
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
                progressDetails.setText(UiLanguage.translate(this, "Cancelamento solicitado ao login Microsoft…"));
            } catch (Exception error) {
                setStatus("Falha ao solicitar cancelamento", safeMessage(error));
            }
            return;
        }
        if (!busy && !conversionBusy) return;
        if (conversionBusy && !busy) {
            cancelButton.setEnabled(false);
            progressDetails.setText(UiLanguage.translate(this, "Cancelamento solicitado ao serviço. A operação vai parar em um ponto seguro…"));
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
        progressDetails.setText(UiLanguage.translate(this, "Cancelamento solicitado. A operação vai parar em um ponto seguro…"));
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
                .setTitle(UiLanguage.translate(this, "Baixar arquivos oficiais do Minecraft?"))
                .setMessage(UiLanguage.translate(this, "Este fluxo baixa arquivos oficiais da Mojang, verifica tamanho e SHA-1, mas não autentica uma conta nem concede uma licença. Prossiga apenas se você tiver uma licença válida de Minecraft Java Edition.") + extra)
                .setNegativeButton(UiLanguage.translate(this, "Voltar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Continuar"), (dialog, which) -> startOfficialDownloadNow(allAssets))
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
                        .setTitle(UiLanguage.translate(this, "Notificações desativadas"))
                        .setMessage(UiLanguage.translate(this, "A preparação pode continuar, mas o Android talvez não mostre a notificação na área de notificações. Você pode permitir isso nas configurações do app."))
                        .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                        .setPositiveButton(UiLanguage.translate(this, "Continuar mesmo assim"), (dialog, which) -> startBackgroundPreparationNow())
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
                        .setTitle(UiLanguage.translate(this, "Notificações desativadas"))
                        .setMessage(UiLanguage.translate(this, "A conversão pode continuar, mas o progresso e a conclusão talvez não apareçam na área de notificações."))
                        .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                        .setPositiveButton(UiLanguage.translate(this, "Continuar sem notificações"), (dialog, which) -> startFullConversionNow())
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
                        .setTitle(UiLanguage.translate(this, "Notificações desativadas"))
                        .setMessage(UiLanguage.translate(this, "O login pode continuar, mas o Android talvez não mostre a notificação na área de notificações."))
                        .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                        .setPositiveButton(UiLanguage.translate(this, "Continuar sem notificações"), (dialog, which) -> startMicrosoftSignInNow())
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
                .setTitle(UiLanguage.translate(this, "Notificações desativadas"))
                .setMessage(UiLanguage.translate(this, "O download pode continuar em segundo plano, mas o progresso talvez não apareça na área de notificações. Você pode permitir notificações nas configurações do Android."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Continuar sem notificações"), (dialog, which) -> confirmOfficialDownload(allAssets))
                .show();
    }

    private void startOfficialDownloadNow(boolean allAssets) {
        if (downloadBusy || busy || conversionBusy) return;
        downloadBusy = true;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText(UiLanguage.translate(this, "Iniciando serviço de download em segundo plano…"));
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
        progressDetails.setText(UiLanguage.translate(this, "Cancelamento solicitado. Arquivos parciais não verificados serão descartados."));
        try {
            startService(new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_CANCEL));
        } catch (Exception error) {
            setStatus("Falha ao solicitar cancelamento", safeMessage(error));
        }
    }

    private void handleDownloadProgress(Intent intent) {
        downloadBusy = true;
        progressBar.setVisibility(View.VISIBLE);
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
        progressDetails.setText(UiLanguage.translate(this, stage == null ? "Download" : stage)
                + (item == null || item.isEmpty() ? "" : "\n" + UiLanguage.translate(this, "Item: ") + item)
                + "\n" + UiLanguage.translate(this, "Contagem: ") + fileCount
                + "\n" + UiLanguage.translate(this, "Arquivo atual: ") + bytes
                + "\n" + UiLanguage.translate(this, "Total conhecido: ") + aggregate);
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
            progressDetails.setText(UiLanguage.translate(this, "Destino: files/minecraft\nBibliotecas: ") + libraries
                    + UiLanguage.translate(this, assets > 0 ? "\nAssets baixados: " : "\nAssets completos: ainda não baixados")
                    + (assets > 0 ? assets : "")
                    + (manifestSha1 == null || manifestSha1.isEmpty() ? ""
                    : UiLanguage.translate(this, "\nSHA-1 do JSON: ") + manifestSha1));
            setStatus("Download oficial concluído", message == null ? "Arquivos verificados." : message);
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText(UiLanguage.translate(this, ""));
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
        progressDetails.setText(UiLanguage.translate(this, "Preparando Java 25 ARM64 e iniciando o exportador completo…"));
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
        progressDetails.setText(UiLanguage.translate(this, "Iniciando serviço de preparação em segundo plano…"));
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
        progressDetails.setText(UiLanguage.translate(this, stage == null ? "Preparação" : stage)
                + (detail == null || detail.isEmpty() ? "" : "\n" + UiLanguage.translate(this, detail))
                + (total > 0 ? "\n" + UiLanguage.translate(this, "Etapa: ") + completed + " / " + total : ""));
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
                progressDetails.setText(UiLanguage.translate(this, "Saída validada: .local/horizon-client/26.3"));
                setStatus("Conversão Horizon completa validada",
                        (message == null ? "O serviço concluiu e validou a árvore de recursos." : message)
                                + "\n\nA validação foi feita em segundo plano. Agora teste “Iniciar com conta offline”.");
            } else {
                progressBar.setVisibility(View.GONE);
                progressDetails.setText(UiLanguage.translate(this, ""));
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
            progressDetails.setText(UiLanguage.translate(this, "Plano: .local/horizon-input/26.3/conversion-plan.json") +
                     "\nExportações diretas: .local/horizon-input/26.3/direct-export"
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
            progressDetails.setText(UiLanguage.translate(this, ""));
            if (error instanceof InterruptedException || cancelRequested.get()) {
                setStatus("Preparação cancelada", safeMessage(error));
            } else {
                setStatus("Preparação para conversão falhou", safeMessage(error));
            }
        }
    }

    private void refreshOfflineUi() {
        String playerName = OfflineProfile.loadName(this);
        if (offlineStatusText != null) offlineStatusText.setText(
                UiLanguage.translate(this, "Nick offline: ") + playerName);
        if (offlineNameButton != null) offlineNameButton.setText(
                UiLanguage.translate(this, "Nick: ") + playerName);
        refreshOfflineDashboard();
    }

    private void showOfflineNameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        input.setText(OfflineProfile.loadName(this));
        input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Nick offline"))
                .setMessage(UiLanguage.translate(this, "Use de 3 a 16 letras, números ou sublinhados."))
                .setView(input)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Salvar"), (dialog, which) -> {
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
        progressDetails.setText(UiLanguage.translate(this, "Verificando a saída Horizon em segundo plano…"));
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
                    progressDetails.setText(UiLanguage.translate(this, ""));
                    showRuntimeError("Recursos Horizon inválidos",
                            validationFailure + "\n\nNenhum jogo foi iniciado.");
                    return;
                }
                progressBar.setVisibility(View.GONE);
                progressDetails.setText(UiLanguage.translate(this, "Recursos Horizon validados."));
                callback.onValidated(validatedSummary);
            });
        });
    }

    private void openOfflineClient() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy || runtimeInstallBusy) return;
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca Horizon ausente",
                    "O APK precisa conter o carregador nativo libhorizon_android.so para ARM64.");
            return;
        }
        ensureRuntimeInstalled(() -> validateResourcesBeforeLaunch(this::launchOfflineClientAfterValidation));
    }

    private void launchOfflineClientAfterValidation(String resourceSummary) {
        try {
            String playerName = OfflineProfile.loadName(this);
            GameInstanceManager.Instance instance = GameInstanceManager.getSelectedInstance(this);
            GameInstanceManager.markLastPlayed(this, instance.id);
            MobilePerformanceProfile.applyInitialOptions(this, instance.id);
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
            intent.putExtra(NativeGameActivity.EXTRA_INSTANCE_ID, instance.id);
            intent.putExtra(NativeGameActivity.EXTRA_PLAYER_NAME, playerName);
            if (pendingQuickPlayServer != null) {
                intent.putExtra(NativeGameActivity.EXTRA_QUICK_PLAY_SERVER, pendingQuickPlayServer);
            }
            if (pendingQuickPlayWorld != null) {
                intent.putExtra(NativeGameActivity.EXTRA_QUICK_PLAY_WORLD, pendingQuickPlayWorld);
            }
            startActivity(intent);
            clearPendingQuickPlay();
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
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy || runtimeInstallBusy) return;
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca Horizon ausente",
                    "O APK precisa conter o carregador nativo libhorizon_android.so para ARM64.");
            return;
        }
        ensureRuntimeInstalled(() -> validateResourcesBeforeLaunch(this::continueNativeClientAfterValidation));
    }

    private void continueNativeClientAfterValidation(String resourceSummary) {
        if (!MicrosoftAuthentication.isValidClientId(BuildConfig.HORIZON_MSA_CLIENT_ID)) {
            showRuntimeError("Autenticação ainda não configurada",
                    resourceSummary + "\n\nPara login real, o responsável pelo projeto precisa registrar um App ID próprio da Microsoft e habilitar o fluxo de dispositivo. Builds sem HORIZON_MSA_CLIENT_ID não podem autenticar.");
            return;
        }
        String refreshToken;
        try {
            MicrosoftAccountStore.Account active = MicrosoftAccountStore.active(this);
            if (active == null) {
                refreshToken = null;
            } else {
                refreshToken = EncryptedRefreshTokenStore.load(this, active.uuid);
            }
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
        enabled.setText(UiLanguage.translate(this, "Ativar controles na tela do jogo"));
        enabled.setTextColor(0xff18213a);
        enabled.setChecked(prefs.getBoolean(NativeTouchControlsView.KEY_ENABLED, true));
        panel.addView(enabled, params(-1, -2));

        CheckBox leftHanded = new CheckBox(this);
        leftHanded.setText(UiLanguage.translate(this, "Layout canhoto (joystick à direita)"));
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
                sizeLabel.setText(UiLanguage.translate(MainActivity.this, "Tamanho dos controles: ") + (70 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sizeLabel.setText(UiLanguage.translate(this, "Tamanho dos controles: ") + (70 + size.getProgress()) + "%");

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
                opacityLabel.setText(UiLanguage.translate(MainActivity.this, "Opacidade: ") + (35 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        opacityLabel.setText(UiLanguage.translate(this, "Opacidade: ") + (35 + opacity.getProgress()) + "%");

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
                sensitivityLabel.setText(UiLanguage.translate(MainActivity.this, "Sensibilidade da câmera: ") + (50 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sensitivityLabel.setText(UiLanguage.translate(this, "Sensibilidade da câmera: ") + (50 + sensitivity.getProgress()) + "%");

        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Controles Touch"))
                .setMessage(UiLanguage.translate(this, "Joystick, câmera por arrasto e botões de ação. Há layout canhoto, tamanho, opacidade e sensibilidade ajustáveis. Durante o jogo, toque no ícone superior para ocultar ou mostrar os controles."))
                .setView(panel)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Salvar"), (dialog, which) -> {
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
        List<MicrosoftAccountStore.Account> accounts = MicrosoftAccountStore.list(this);
        MicrosoftAccountStore.Account active = MicrosoftAccountStore.active(this);
        boolean signedIn = active != null;
        String detail = signedIn
                ? "Conta selecionada: " + active.name + "\nContas Microsoft salvas: " + accounts.size()
                    + "\nA licença é verificada novamente antes de iniciar o jogo."
                : "Nenhuma conta verificada neste dispositivo.";
        accountStatusText.setText(UiLanguage.translate(this, detail));
        if (microsoftLoginButton != null) microsoftLoginButton.setText(UiLanguage.translate(this,
                signedIn ? "Adicionar/atualizar conta Microsoft" : "Entrar com Microsoft"));
        if (microsoftSignOutButton != null) microsoftSignOutButton.setVisibility(
                signedIn ? View.VISIBLE : View.GONE);
        refreshMicrosoftAccountDashboard();
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
        progressDetails.setText(UiLanguage.translate(this, "Iniciando autenticação Microsoft…"));
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
        if (accountStatusText != null) {
            accountStatusText.setText(UiLanguage.translate(this,
                    (stage == null ? "Login Microsoft" : stage)
                            + (detail == null || detail.isEmpty() ? "" : " • " + detail)));
        }
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText(UiLanguage.translate(this, stage == null ? "Login Microsoft" : stage)
                + (detail == null || detail.isEmpty() ? "" : "\n" + UiLanguage.translate(this, detail)));
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
                .setTitle(UiLanguage.translate(this, "Entre com a Microsoft"))
                .setView(instructions)
                .setPositiveButton(UiLanguage.translate(this, "Abrir página"), (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)));
                    } catch (Exception error) {
                        showRuntimeError("Não foi possível abrir o navegador", safeMessage(error));
                    }
                })
                .setNeutralButton(UiLanguage.translate(this, "Copiar código"), (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Código de login Microsoft", code));
                        setStatus("Código copiado", "Conclua o login na página oficial da Microsoft.");
                    }
                })
                .setNegativeButton(UiLanguage.translate(this, "Fechar"), null)
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
            if (pendingQuickPlayServer != null) {
                openNativeClient();
            }
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
                .setTitle(UiLanguage.translate(this, "Remover conta selecionada?"))
                .setMessage(UiLanguage.translate(this, "Somente a conta Microsoft ativa e seu token criptografado serão removidos. Outras contas e perfis offline serão preservados."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Remover"), (dialog, which) -> {
                    try {
                        MicrosoftAccountStore.Account active = MicrosoftAccountStore.active(this);
                        if (active != null) {
                            EncryptedRefreshTokenStore.clear(this, active.uuid);
                            MicrosoftAccountStore.remove(this, active.uuid);
                        } else {
                            EncryptedRefreshTokenStore.clear(this);
                            getSharedPreferences("microsoft_account_state", MODE_PRIVATE).edit().clear().commit();
                        }
                        refreshAccountUi();
                        setStatus("Conta removida", "As demais contas salvas continuam disponíveis no gerenciador de perfis.");
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
        progressDetails.setText(UiLanguage.translate(this, "Renovando sessão Microsoft e verificando a licença…"));
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
                EncryptedRefreshTokenStore.save(this, account.uuid, account.refreshToken);
                MicrosoftAccountStore.upsert(this, account.uuid, account.name, account.xuid,
                        System.currentTimeMillis());
                runOnUiThread(() -> {
                    launchBusy = false;
                    updateActionEnablement();
                    refreshAccountUi();
                    File sessionFile = null;
                    try {
                        GameInstanceManager.Instance instance = GameInstanceManager.getSelectedInstance(this);
                        GameInstanceManager.markLastPlayed(this, instance.id);
                        MobilePerformanceProfile.applyInitialOptions(this, instance.id);
                        new File(getFilesDir(), "horizon-client-result.txt").delete();
                        new File(getFilesDir(), "horizon-native-smoke-result.txt").delete();
                        new File(getFilesDir(), "horizon-native-smoke.flag").delete();
                        pendingNativeClientResult = true;
                        pendingNativeSmokeResult = false;
                        sessionFile = writeOneShotSession(account);
                        Os.setenv("HORIZON_SESSION_FILE", sessionFile.getAbsolutePath(), true);
                        sessionEnvSet = true;
                        Intent gameIntent = new Intent(this, NativeGameActivity.class)
                                .putExtra(NativeGameActivity.EXTRA_TOUCH_CONTROLS, true)
                                .putExtra(NativeGameActivity.EXTRA_INSTANCE_ID, instance.id)
                                .putExtra(NativeGameActivity.EXTRA_SESSION_FILE, sessionFile.getAbsolutePath());
                        if (pendingQuickPlayServer != null) {
                            gameIntent.putExtra(NativeGameActivity.EXTRA_QUICK_PLAY_SERVER,
                                    pendingQuickPlayServer);
                        }
                        if (pendingQuickPlayWorld != null) {
                            gameIntent.putExtra(NativeGameActivity.EXTRA_QUICK_PLAY_WORLD,
                                    pendingQuickPlayWorld);
                        }
                        startActivity(gameIntent);
                        clearPendingQuickPlay();
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
                        try {
                            MicrosoftAccountStore.Account active = MicrosoftAccountStore.active(this);
                            if (active != null) {
                                EncryptedRefreshTokenStore.clear(this, active.uuid);
                                MicrosoftAccountStore.remove(this, active.uuid);
                            }
                        } catch (Exception ignored) {}
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

    private void showLogsDialog() {
        StringBuilder report = new StringBuilder();
        report.append("HORIZON LOG VIEWER\n");
        report.append("Minecraft ").append(OfficialMinecraftInstaller.VERSION).append("\n");
        report.append("Runtime updater: ").append(HorizonRuntimeUpdateService.lastStatus(this)).append("\n");
        report.append("Aviso: logs podem conter endereços de servidores, nicknames e mensagens de chat.\n\n");

        File gameHome;
        try {
            gameHome = GameInstanceManager.getSelectedInstanceHome(this);
        } catch (IOException error) {
            showRuntimeError("Não foi possível abrir os logs da instância", safeMessage(error));
            return;
        }
        File latestLog = new File(new File(gameHome, "logs"), "latest.log");
        appendLogTail(report, "Client log: logs/latest.log", latestLog, 96 * 1024);
        if (!latestLog.isFile()) {
            report.append("O log do cliente ainda não existe; inicie o Horizon para criar o arquivo.\n");
        }
        appendLogTail(report, "Native client startup",
                new File(getFilesDir(), "horizon-client-result.txt"), 8 * 1024);
        appendLogTail(report, "Vulkan smoke test",
                new File(getFilesDir(), "horizon-native-smoke-result.txt"), 8 * 1024);

        File crashDir = new File(gameHome, "crash-reports");
        File[] crashes = crashDir.listFiles(File::isFile);
        if (crashes != null && crashes.length > 0) {
            java.util.Arrays.sort(crashes, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            appendLogTail(report, "Latest crash report: " + crashes[0].getName(),
                    crashes[0], 48 * 1024);
        } else {
            report.append("\n--- Crash reports ---\nNenhum crash-report encontrado.\n");
        }
        if (report.length() < 100) {
            report.append("\nAinda não existem logs. Abra o Horizon ou execute o teste Vulkan primeiro.\n");
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        TextView logText = text(report.toString(), 11, 0xffe2dcf8, false);
        logText.setTypeface(monoTypeface);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(logText, params(-1, -2));
        scroll.setBackgroundColor(0xff100f1a);

        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Logs do Horizon"))
                .setView(scroll)
                .setNegativeButton(UiLanguage.translate(this, "Fechar"), null)
                .setNeutralButton(UiLanguage.translate(this, "Exportar diagnóstico"), (dialog, which) -> exportDiagnostics())
                .show();
    }

    private void appendLogTail(StringBuilder output, String title, File file, int maxBytes) {
        output.append("\n--- ").append(title).append(" ---\n");
        if (!file.isFile()) {
            output.append("Arquivo não encontrado: ").append(file.getAbsolutePath()).append("\n");
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0L, length - maxBytes);
            if (start > 0) {
                output.append("[exibindo os últimos ").append(maxBytes)
                        .append(" bytes de ").append(length).append("]\n");
            }
            input.seek(start);
            byte[] buffer = new byte[(int) Math.min((long) maxBytes, length - start)];
            input.readFully(buffer);
            output.append(new String(buffer, StandardCharsets.UTF_8));
            if (output.length() > 180_000) {
                output.setLength(180_000);
                output.append("\n[visualização limitada para preservar memória]\n");
            }
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                output.append('\n');
            }
        } catch (Exception error) {
            output.append("Falha ao ler log: ").append(safeMessage(error)).append("\n");
        }
    }

    private void exportDiagnostics() {
        if (busy || conversionBusy || downloadBusy || authBusy || launchBusy || diagnosticsBusy) {
            showRuntimeError("Ação indisponível",
                    "Termine a operação atual antes de preparar o pacote de diagnóstico.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Exportar diagnóstico multiplayer?"))
                .setMessage(UiLanguage.translate(this, "Será criado um ZIP com latest.log, até três logs arquivados recentes, o relatório de falha mais recente e metadados do aparelho/build. O relatório não inclui arquivos de sessão ou tokens de autenticação. O log pode conter o endereço do servidor, nicknames e mensagens de chat; compartilhe somente depois de revisar."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Preparar ZIP"), (dialog, which) -> buildDiagnosticArchive())
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

                File horizonHome = GameInstanceManager.getSelectedInstanceHome(this);
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
        if (runtimeInstallBusy || launchBusy) return;
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca nativa ausente",
                    "O teste Vulkan exige o carregador libhorizon_android.so empacotado para arm64-v8a.");
            return;
        }
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            showRuntimeError("Arquitetura não suportada", "O teste de renderer requer ARM64.");
            return;
        }
        ensureRuntimeInstalled(this::launchNativeSmokeTest);
    }

    private void launchNativeSmokeTest() {
        File marker = new File(getFilesDir(), "horizon-native-smoke.flag");
        marker.delete(); // Legacy flag: smoke mode is now passed explicitly in the Intent.
        new File(getFilesDir(), "horizon-native-smoke-result.txt").delete();
        new File(getFilesDir(), "horizon-client-result.txt").delete();
        pendingNativeSmokeResult = true;
        pendingNativeClientResult = false;
        try {
            String instanceId = GameInstanceManager.getSelectedInstance(this).id;
            startActivity(new Intent(this, NativeGameActivity.class)
                    .putExtra(NativeGameActivity.EXTRA_TOUCH_CONTROLS, false)
                    .putExtra(NativeGameActivity.EXTRA_INSTANCE_ID, instanceId)
                    .putExtra(NativeGameActivity.EXTRA_NATIVE_SMOKE_TEST, true));
            setStatus("Teste Vulkan iniciado", "Este teste cria uma superfície WGPU, solicita um adaptador Vulkan e tenta apresentar um frame de cor simples; não carrega os recursos do Minecraft.");
        } catch (Exception error) {
            pendingNativeSmokeResult = false;
            showRuntimeError("Não foi possível abrir NativeActivity", safeMessage(error));
        }
    }

    /** Ensures a verified runtime exists and waits for any startup update already in progress. */
    private void ensureRuntimeInstalled(Runnable onReady) {
        if (runtimeInstallBusy || isFinishing() || isDestroyed()) return;
        runtimeInstallBusy = true;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText(UiLanguage.translate(this, "Verificando a runtime Android…"));
        setStatus("Preparando runtime Horizon",
                "Verificando a versão Android disponível antes de iniciar o jogo.");

        executor.submit(() -> {
            String version = "";
            String inspectionError = null;
            try {
                version = HorizonRuntimeManager.currentVersion(MainActivity.this);
            } catch (Exception error) {
                inspectionError = safeMessage(error);
            }
            final String installedVersion = version;
            final String checkError = inspectionError;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    finishRuntimeInstallUi();
                    return;
                }

                Runnable verifyAfterCheck = () -> verifyRuntimeForLaunch(onReady, checkError);
                if (installedVersion == null || installedVersion.isEmpty()) {
                    // A missing/invalid runtime always gets a forced attempt, independent of the
                    // automatic-update toggle or cooldown. If startup is already checking, the
                    // service joins that in-flight request rather than starting a second download.
                    progressDetails.setText(UiLanguage.translate(this, "Baixando e verificando a runtime Android mais recente…"));
                    setStatus("Baixando runtime Horizon",
                            "A primeira instalação exige uma runtime Android ARM64 verificada.");
                    HorizonRuntimeUpdateService.checkNow(MainActivity.this, verifyAfterCheck);
                } else {
                    // Do not launch the old runtime while the cold-start freshness check is still
                    // downloading a newer release. Offline use remains possible if that check fails.
                    HorizonRuntimeUpdateService.checkInBackground(MainActivity.this, verifyAfterCheck);
                }
            });
        });
    }

    private void verifyRuntimeForLaunch(Runnable onReady, String previousInspectionError) {
        if (isFinishing() || isDestroyed()) {
            finishRuntimeInstallUi();
            return;
        }
        executor.submit(() -> {
            String version = "";
            String inspectionError = null;
            try {
                version = HorizonRuntimeManager.currentVersion(MainActivity.this);
            } catch (Exception error) {
                inspectionError = safeMessage(error);
            }
            final String verifiedVersion = version;
            final String checkError = inspectionError;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    finishRuntimeInstallUi();
                    return;
                }
                if (verifiedVersion != null && !verifiedVersion.isEmpty()) {
                    finishRuntimeInstallUi();
                    setStatus("Runtime Horizon pronta",
                            "Runtime instalada e verificada: " + verifiedVersion
                                    + ". O jogo pode ser iniciado.");
                    onReady.run();
                    return;
                }

                finishRuntimeInstallUi();
                refreshRuntimeUpdateStatus();
                showRuntimeError("Runtime Horizon indisponível",
                        HorizonRuntimeUpdateService.lastStatus(MainActivity.this)
                                + "\n\nO jogo não será iniciado sem uma runtime Android verificada. "
                                + "Confira a conexão e use “Verificar atualizações” para tentar novamente."
                                + (checkError != null ? "\n\n" + checkError
                                        : (previousInspectionError == null ? ""
                                                : "\n\n" + previousInspectionError)));
                setStatus("Runtime pendente",
                        "Nenhuma runtime verificada está instalada; a inicialização do jogo foi bloqueada.");
            });
        });
    }

    private void finishRuntimeInstallUi() {
        runtimeInstallBusy = false;
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
            progressBar.setIndeterminate(false);
        }
        if (progressDetails != null) progressDetails.setText(UiLanguage.translate(this, ""));
        updateActionEnablement();
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
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, title))
                .setMessage(UiLanguage.translate(this, message))
                .setPositiveButton(UiLanguage.translate(this, "Entendi"), null)
                .show();
    }

    private void clearPendingImport() {
        pendingUri = null;
    }

    private void setBusyUi(boolean importing) {
        updateActionEnablement();
    }

    private void updateActionEnablement() {
        boolean runtimeAvailable = HorizonRuntimeManager.hasActiveRuntimeMarker(this);
        boolean blocked = busy || conversionBusy || downloadBusy || authBusy || launchBusy
                || diagnosticsBusy || runtimeInstallBusy || runtimeUpdateRequestBusy;
        if (importFolderButton != null) importFolderButton.setEnabled(!blocked);
        if (importZipButton != null) importZipButton.setEnabled(!blocked);
        if (downloadBaseButton != null) downloadBaseButton.setEnabled(!blocked);
        if (downloadAssetsButton != null) downloadAssetsButton.setEnabled(!blocked);
        if (prepareInputsButton != null) prepareInputsButton.setEnabled(!blocked);
        if (fullConvertButton != null) fullConvertButton.setEnabled(!blocked);
        if (openEngineButton != null) openEngineButton.setEnabled(!blocked && runtimeAvailable);
        if (smokeTestButton != null) smokeTestButton.setEnabled(!blocked && runtimeAvailable);
        if (exportDiagnosticsButton != null) exportDiagnosticsButton.setEnabled(!blocked);
        if (microsoftLoginButton != null) microsoftLoginButton.setEnabled(!blocked);
        if (microsoftSignOutButton != null) microsoftSignOutButton.setEnabled(!blocked
                && getSharedPreferences("microsoft_account_state", MODE_PRIVATE).getBoolean("hasAccount", false));
        if (offlineNameButton != null) offlineNameButton.setEnabled(!blocked);
        if (offlinePlayButton != null) offlinePlayButton.setEnabled(!blocked && runtimeAvailable);
        if (runtimeUpdateButton != null) {
            refreshRuntimeActionButton();
            runtimeUpdateButton.setEnabled(!blocked);
            runtimeUpdateButton.setAlpha(blocked ? 0.55f : 1f);
        }
        if (cancelButton != null) {
            boolean cancellable = busy || conversionBusy || authBusy;
            cancelButton.setVisibility(cancellable ? View.VISIBLE : View.GONE);
            cancelButton.setEnabled(cancellable);
            cancelButton.setText(UiLanguage.translate(this, authBusy ? "Cancelar login"
                    : fullConversionBusy ? "Cancelar conversão completa"
                    : conversionBusy && !busy ? "Cancelar preparação" : "Cancelar importação"));
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
        statusTitle.setText(UiLanguage.translate(this, title));
        statusDetails.setText(UiLanguage.translate(this, details));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.setBackground(new HorizonBlockDrawable(0xff1a1727, 0xff342d52,
                0xff07060b, 0xff05040a, dp(2), dp(2), dp(4)));
        return card;
    }

    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(UiLanguage.translate(this, label));
        button.setTextSize(13);
        button.setTypeface(buttonTypeface);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        if (primary) {
            int accent = LauncherAppearance.accentColor(this);
            int border = LauncherAppearance.accentBorderColor(this);
            button.setBackground(new HorizonBlockDrawable(accent, border,
                    0xff3a2694, 0xff05040a, dp(2), dp(2), dp(4)));
        } else {
            button.setBackground(new HorizonBlockDrawable(0xff2b2641, 0xff3d3560,
                    0xff07060b, 0xff05040a, dp(2), dp(2), dp(4)));
        }
        button.setTextColor(primary ? Color.WHITE : 0xffc5c7d1);
        button.setOnClickListener(click);
        return button;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(UiLanguage.translate(this, value));
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        text.setTypeface(bold ? pixelTypeface : bodyTypeface);
        return text;
    }

    private static final class HorizonBlockDrawable extends Drawable {
        private final Paint paint = new Paint();
        private final int faceColor;
        private final int bevelColor;
        private final int lipColor;
        private final int outlineColor;
        private final float outlineWidth;
        private final float bevelSize;
        private final float lipSize;
        private int alpha = 255;
        private boolean pressed;
        private ColorFilter colorFilter;

        HorizonBlockDrawable(int faceColor, int bevelColor, int lipColor, int outlineColor,
                             float outlineWidth, float bevelSize, float lipSize) {
            this.faceColor = faceColor;
            this.bevelColor = bevelColor;
            this.lipColor = lipColor;
            this.outlineColor = outlineColor;
            this.outlineWidth = outlineWidth;
            this.bevelSize = bevelSize;
            this.lipSize = lipSize;
            paint.setAntiAlias(false);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            float left = bounds.left;
            float top = bounds.top;
            float right = bounds.right;
            float bottom = bounds.bottom;
            float inset = Math.min(outlineWidth, Math.min(bounds.width(), bounds.height()) / 2f);
            int base = pressed ? shade(faceColor, -8) : faceColor;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(base));
            paint.setColorFilter(colorFilter);
            canvas.drawRect(left + inset, top + inset, right - inset, bottom - inset, paint);

            if (!pressed && bevelSize > 0) {
                paint.setColor(withAlpha(bevelColor));
                canvas.drawRect(left + inset, top + inset, right - inset,
                        Math.min(bottom - inset, top + inset + bevelSize), paint);
                canvas.drawRect(left + inset, top + inset, Math.min(right - inset, left + inset + bevelSize),
                        bottom - inset, paint);
            }
            if (lipSize > 0) {
                paint.setColor(withAlpha(lipColor));
                canvas.drawRect(left + inset, Math.max(top + inset, bottom - inset - lipSize),
                        right - inset, bottom - inset, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(outlineWidth);
            paint.setColor(withAlpha(outlineColor));
            canvas.drawRect(left + outlineWidth / 2f, top + outlineWidth / 2f,
                    right - outlineWidth / 2f, bottom - outlineWidth / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private int withAlpha(int color) {
            return (color & 0x00ffffff) | (alpha << 24);
        }

        private static int shade(int color, int amount) {
            int r = Math.max(0, Math.min(255, ((color >> 16) & 255) + amount));
            int g = Math.max(0, Math.min(255, ((color >> 8) & 255) + amount));
            int b = Math.max(0, Math.min(255, (color & 255) + amount));
            return (r << 16) | (g << 8) | b;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean isPressed = false;
            for (int value : state) if (value == android.R.attr.state_pressed) isPressed = true;
            if (pressed == isPressed) return false;
            pressed = isPressed;
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
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
