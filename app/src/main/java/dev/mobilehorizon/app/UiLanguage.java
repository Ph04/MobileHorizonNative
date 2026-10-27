package dev.mobilehorizon.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Language preference for the MobileHorizon launcher and its native panels.
 * This deliberately does not change Android's locale or Minecraft's own language setting.
 */
final class UiLanguage {
    private static final String PREFS = "mobilehorizon_interface";
    private static final String KEY_LANGUAGE = "language";
    private static final Map<String, String> PT_TO_EN = new HashMap<>();
    private static final Map<String, String> EN_TO_PT = new HashMap<>();

    static {
        pair("PRONTO", "READY");
        pair("Configuração necessária", "SETUP REQUIRED");
        pair("PRONTO PARA JOGAR", "READY TO PLAY");
        pair("Recursos", "Resources");
        pair("Estado", "Status");
        pair("Gerenciar", "Manage");
        pair("Downloads e preparação", "Downloads & setup");
        pair("Arquivos oficiais do Minecraft 26.3, assets e conversão para o Horizon.", "Official Minecraft 26.3 files, assets, and Horizon preparation.");
        pair("Os arquivos são verificados por tamanho e SHA-1. O download completo de assets pode ocupar centenas de MB.", "Files are verified by size and SHA-1. A complete asset download can use hundreds of MB.");
        pair("Baixar Minecraft (arquivos base)", "Download Minecraft (base files)");
        pair("Baixar base + todos os assets", "Download base + all assets");
        pair("Preparar recursos para jogar", "Prepare game resources");
        pair("A conversão completa e a preparação de entradas mostram o progresso nesta tela e na notificação.", "Full conversion and input preparation show progress here and in the notification.");
        pair("Preparar entradas", "Prepare inputs");
        pair("Conversão completa", "Full conversion");
        pair("Gerenciador de recursos", "Resource manager");
        pair("Importar, inspecionar e organizar recursos já preparados.", "Import, inspect, and organize prepared resources.");
        pair("Gerencie recursos compatíveis com o runtime instalado. A saída preparada do Minecraft fica no armazenamento privado do app.", "Manage resources compatible with the installed runtime. Prepared Minecraft files are stored in the app's private storage.");
        pair("Abrir gerenciador de recursos", "Open resource manager");
        pair("Importar pasta preparada", "Import prepared folder");
        pair("Importar ZIP preparado", "Import prepared ZIP");
        pair("Contas", "Accounts");
        pair("Conta Microsoft opcional; o perfil offline continua disponível.", "A Microsoft account is optional; the offline profile remains available.");
        pair("Nenhuma conta verificada neste dispositivo.", "No account has been verified on this device.");
        pair("Servidores em modo online exigem autenticação oficial. Servidores configurados para modo offline podem aceitar seu nickname local.", "Online-mode servers require official authentication. Servers configured for offline mode may accept your local nickname.");
        pair("Entrar com Microsoft", "Sign in with Microsoft");
        pair("Jogar com conta Microsoft", "Play with Microsoft account");
        pair("Remover conta salva", "Remove saved account");
        pair("Logs e diagnóstico", "Logs & diagnostics");
        pair("Consultar logs locais, testar o renderizador e exportar um pacote de diagnóstico.", "View local logs, test the renderer, and export a diagnostic bundle.");
        pair("Testar Vulkan", "Test Vulkan");
        pair("Abrir logs do Horizon", "Open Horizon logs");
        pair("Exportar diagnóstico", "Export diagnostics");
        pair("Compatibilidade do aparelho", "Device compatibility");
        pair("MobileHorizon • Horizon engine • recursos oficiais verificados", "MobileHorizon • Horizon engine • verified official files");
        pair("INSTÂNCIAS", "INSTANCES");
        pair("NAVEGAÇÃO", "NAVIGATION");
        pair("Início", "Home");
        pair("Downloads", "Downloads");
        pair("Recursos", "Resources");
        pair("Logs", "Logs");
        pair("Atualizações", "Updates");
        pair("Estado do Horizon", "Horizon status");
        pair("Pronto", "Ready");
        pair("Recursos oficiais e motor nativo são gerenciados separadamente.", "Official game files and the native engine are managed separately.");
        pair("Cancelar operação", "Cancel operation");
        pair("Cancelar download", "Cancel download");
        pair("Motor Horizon", "Horizon engine");
        pair("Release de PC acompanhada: ", "Tracked PC release: ");
        pair("Verificar atualizações", "Check for updates");
        pair("Baixar runtime mais recente", "Download latest runtime");
        pair("Atualizando runtime…", "Updating runtime…");
        pair("Baixando runtime…", "Downloading runtime…");
        pair("Recursos preparados para Minecraft ", "Resources prepared for Minecraft ");
        pair(" • saída Horizon encontrada", " • Horizon output found");
        pair("Ainda não preparado • baixe e prepare os recursos antes de jogar", "Not prepared yet • download and prepare resources before playing");
        pair("Substituir importação atual?", "Replace current import?");
        pair("A importação nova só será ativada depois de terminar e passar pela validação inicial. A cópia anterior será preservada se ocorrer uma falha.", "The new import is activated only after it finishes and passes initial validation. The previous copy is preserved if anything fails.");
        pair("Manter atual", "Keep current");
        pair("Substituir", "Replace");
        pair("Baixar arquivos oficiais do Minecraft?", "Download official Minecraft files?");
        pair("Este fluxo baixa arquivos oficiais da Mojang, verifica tamanho e SHA-1, mas não autentica uma conta nem concede uma licença. Prossiga apenas se você tiver uma licença válida de Minecraft Java Edition.", "This process downloads official Mojang files and verifies their size and SHA-1, but it does not authenticate an account or grant a license. Continue only if you have a valid Minecraft Java Edition license.");
        pair("Voltar", "Back");
        pair("Continuar", "Continue");
        pair("Notificações desativadas", "Notifications are disabled");
        pair("A preparação pode continuar, mas o Android talvez não mostre a notificação na área de notificações. Você pode permitir isso nas configurações do app.", "Preparation can continue, but Android may not show a notification. You can allow notifications in the app settings.");
        pair("Continuar mesmo assim", "Continue anyway");
        pair("A conversão pode continuar, mas o progresso e a conclusão talvez não apareçam na área de notificações.", "Conversion can continue, but progress and completion may not appear in notifications.");
        pair("Continuar sem notificações", "Continue without notifications");
        pair("O login pode continuar, mas o Android talvez não mostre a notificação na área de notificações.", "Sign-in can continue, but Android may not show a notification.");
        pair("O download pode continuar em segundo plano, mas o progresso talvez não apareça na área de notificações. Você pode permitir notificações nas configurações do Android.", "The download can continue in the background, but progress may not appear in notifications. You can allow notifications in Android settings.");
        pair("Iniciando serviço de download em segundo plano…", "Starting background download service…");
        pair("Cancelamento solicitado. Arquivos parciais não verificados serão descartados.", "Cancellation requested. Unverified partial files will be discarded.");
        pair("Preparando Java 25 ARM64 e iniciando o exportador completo…", "Preparing Java 25 ARM64 and starting the full exporter…");
        pair("Iniciando serviço de preparação em segundo plano…", "Starting background preparation service…");
        pair("Saída validada: .local/horizon-client/26.3", "Validated output: .local/horizon-client/26.3");
        pair("Nick offline", "Offline nickname");
        pair("Use de 3 a 16 letras, números ou sublinhados.", "Use 3–16 letters, numbers, or underscores.");
        pair("Salvar", "Save");
        pair("Verificando a saída Horizon em segundo plano…", "Verifying Horizon output in the background…");
        pair("Recursos Horizon validados.", "Horizon resources validated.");
        pair("Ativar controles na tela do jogo", "Enable in-game touch controls");
        pair("Layout canhoto (joystick à direita)", "Left-handed layout (joystick on the right)");
        pair("Tamanho dos controles: ", "Control size: ");
        pair("Opacidade: ", "Opacity: ");
        pair("Sensibilidade da câmera: ", "Camera sensitivity: ");
        pair("Controles Touch", "Touch controls");
        pair("Joystick, câmera por arrasto e botões de ação. Há layout canhoto, tamanho, opacidade e sensibilidade ajustáveis. Durante o jogo, toque no ícone superior para ocultar ou mostrar os controles.", "Joystick, swipe camera, and action buttons. Left-handed layout, size, opacity, and sensitivity are adjustable. During gameplay, tap the top icon to show or hide the controls.");
        pair("Entre com a Microsoft", "Sign in with Microsoft");
        pair("Abrir página", "Open page");
        pair("Copiar código", "Copy code");
        pair("Fechar", "Close");
        pair("Remover conta salva?", "Remove saved account?");
        pair("O token de renovação criptografado e os dados públicos da conta serão removidos deste dispositivo.", "The encrypted refresh token and public account data will be removed from this device.");
        pair("Remover", "Remove");
        pair("Logs do Horizon", "Horizon logs");
        pair("Exportar diagnóstico multiplayer?", "Export multiplayer diagnostics?");
        pair("Será criado um ZIP com latest.log, até três logs arquivados recentes, o relatório de falha mais recente e metadados do aparelho/build. O relatório não inclui arquivos de sessão ou tokens de autenticação. O log pode conter o endereço do servidor, nicknames e mensagens de chat; compartilhe somente depois de revisar.", "A ZIP will be created with latest.log, up to three recent archived logs, the latest crash report, and device/build metadata. It excludes session files and authentication tokens. Logs may contain server addresses, nicknames, and chat messages; review them before sharing.");
        pair("Preparar ZIP", "Create ZIP");
        pair("Entendi", "Got it");
        pair("🌐 Idioma", "🌐 Language");
        pair("Painel de texto", "Text input panel");
        pair("DIGITAÇÃO DO MINECRAFT", "MINECRAFT TEXT INPUT");
        pair("Digite abaixo e envie para o campo ativo ou para o chat. O texto aparece no jogo depois do envio.", "Type below and send to the focused field or chat. The text appears in-game after sending.");
        pair("Use o botão Enviar do teclado ou o botão abaixo.", "Use the keyboard's Send button or the button below.");
        pair("Enviar  ↑", "Send  ↑");
        pair("Mensagem, comando ou texto…", "Message, command, or text…");
        pair("Texto do botão (ex.: MAP)", "Button label (e.g. MAP)");
        pair("Tecla (ex.: G, SPACE, F6)", "Key (e.g. G, SPACE, F6)");
        pair("Tecla, ex.: G, SPACE, F6", "Key, e.g. G, SPACE, F6");
        pair("Alternância: ativar/desativar", "Toggle mode: on/off");
        pair("Adicionar botão", "Add button");
        pair("Escolha entre manter a tecla pressionada enquanto segura ou alternar entre ativar e desativar.", "Choose whether to hold the key while pressed or toggle it on and off.");
        pair("Informe um nome curto e uma tecla válida.", "Enter a short label and a valid key.");
        pair("Menu do jogo", "Game overlay menu");
        pair("CONTROLES ATIVOS", "CONTROLS ENABLED");
        pair("CONTROLES OCULTOS", "CONTROLS HIDDEN");
        pair("Personalizar layout", "Customize layout");
        pair("Mover, redimensionar e configurar os botões", "Move, resize, and configure buttons");
        pair("Ajustes dos controles", "Control settings");
        pair("Sensibilidade, opacidade e preferências", "Sensitivity, opacity, and preferences");
        pair("Ocultar controles", "Hide controls");
        pair("Mostrar controles", "Show controls");
        pair("Deixar a tela do jogo mais livre", "Clear more space on the game screen");
        pair("Exibir os botões de toque", "Show touch buttons");
        pair("Fechar jogo", "Close game");
        pair("Encerrar a sessão e voltar à tela inicial", "End the session and return to the launcher");
        pair("Fechar o jogo?", "Close the game?");
        pair("Você voltará à tela inicial do MobileHorizon.", "You will return to the MobileHorizon launcher.");
        pair("MOBILEHORIZON  •  CONTROLES NATIVOS", "MOBILEHORIZON  •  NATIVE CONTROLS");
        pair("Tamanho ", "Size ");
        pair("Tamanho geral ", "Overall size ");
        pair("Opacidade geral ", "Overall opacity ");
        pair("Sensibilidade da câmera ", "Camera sensitivity ");
        pair("Distância de encaixe ", "Snap spacing ");
        pair("Formato: ", "Shape: ");
        pair("Cor: ", "Color: ");
        pair("Corrida automática ao empurrar até a frente", "Auto-sprint when pushing the joystick forward");
        pair("Excluir", "Delete");
        pair("Ajustes dos controles salvos.", "Control settings saved.");
        pair("Botões predefinidos", "Preset buttons");
        pair("Desmarque para excluir da tela. Marque novamente para restaurar. Posições e cores salvas são mantidas.", "Uncheck to remove a button from the screen. Check it again to restore it. Saved positions and colors are kept.");
        pair("Restaurar todos", "Restore all");
        pair("Botões predefinidos restaurados.", "Preset buttons restored.");
        pair("Gerenciar / restaurar botões predefinidos", "Manage / restore preset buttons");
        pair("Botões Clássicos", "Classic buttons");
        pair("Ajustes", "Settings");
        pair("Arraste para mover · toque para editar · + adiciona · ✓ salva", "Drag to move · tap to edit · + adds · ✓ saves");
        pair("Layout dos botões salvo.", "Button layout saved.");
        pair("Não foi possível abrir a personalização sem interromper o jogo.", "Could not open customization without interrupting the game.");
        pair("Informe uma tecla válida para essa função.", "Enter a valid key for this action.");
        pair("Use um texto de até 7 caracteres.", "Use a label of up to 7 characters.");
        pair("Importar arquivos (.zip / .hmod)", "Import files (.zip / .hmod)");
        pair("Pasta de shader", "Shader folder");
        pair("Atualizar lista", "Refresh list");
        pair("Pronto.", "Ready.");
        pair("Organize os recursos instalados e escolha o que será carregado no Minecraft.", "Organize installed resources and choose what will be loaded into Minecraft.");
        pair("Nenhum recurso compatível instalado. Importe um arquivo ou uma pasta de shader pack.", "No compatible resources installed. Import a file or a shader pack folder.");
        pair("Nada instalado aqui ainda. Use Importar arquivos acima para adicionar recursos.", "Nothing installed here yet. Use Import files above to add resources.");
        pair("Instalar", "Install");
        pair("Excluir recurso?", "Delete resource?");
        pair(" será removido do armazenamento do jogo. Essa ação não pode ser desfeita.", " will be removed from game storage. This action cannot be undone.");
        pair("Não foi possível abrir o seletor: ", "Could not open picker: ");
        pair("Não foi possível abrir o seletor de pastas: ", "Could not open folder picker: ");
        pair("Cancelar", "Cancel");
        // Additional UI strings used by progress, diagnostics, resource, and control panels.
        pair("Preparado para Minecraft ", "Prepared for Minecraft ");
        pair("Ainda não preparado • baixe e prepare os recursos antes de jogar", "Not prepared yet • download and prepare resources before playing");
        pair("Nick offline: ", "Offline nickname: ");
        pair("Nick: ", "Nickname: ");
        pair("Transferido: ", "Transferred: ");
        pair(" de ", " of ");
        pair("Importação em andamento", "Import in progress");
        pair("Os dados estão sendo copiados para uma área temporária. Você pode cancelar sem substituir a importação anterior.", "Data is being copied to a temporary location. You can cancel without replacing the previous import.");
        pair("Recursos Horizon importados", "Horizon resources imported");
        pair("Importação cancelada", "Import canceled");
        pair("Não foi possível importar", "Import failed");
        pair("Falha ao solicitar cancelamento", "Could not request cancellation");
        pair("Baixando Minecraft e assets completos", "Downloading Minecraft and all assets");
        pair("Baixando arquivos base oficiais", "Downloading official base files");
        pair("Não foi possível iniciar o download", "Could not start download");
        pair("Download oficial em andamento", "Official download in progress");
        pair("Download oficial concluído", "Official download complete");
        pair("Download não concluído", "Download incomplete");
        pair("Verifique a conexão e tente novamente.", "Check your connection and try again.");
        pair("Conversão completa em andamento", "Full conversion in progress");
        pair("Não foi possível iniciar a conversão completa", "Could not start full conversion");
        pair("Preparação em andamento", "Preparation in progress");
        pair("Não foi possível iniciar a preparação", "Could not start preparation");
        pair("Conversão Horizon completa validada", "Full Horizon conversion validated");
        pair("Conversão completa cancelada", "Full conversion canceled");
        pair("Conversão completa falhou", "Full conversion failed");
        pair("Preparação cancelada", "Preparation canceled");
        pair("Preparação para conversão falhou", "Conversion preparation failed");
        pair("Nick offline salvo", "Offline nickname saved");
        pair("Nick inválido", "Invalid nickname");
        pair("Verificando recursos do jogo", "Verifying game resources");
        pair("Recursos Horizon inválidos", "Invalid Horizon resources");
        pair("Nenhum jogo foi iniciado.", "No game was started.");
        pair("Biblioteca Horizon ausente", "Horizon library missing");
        pair("O APK precisa conter o carregador nativo libhorizon_android.so para ARM64.", "The APK must include the native libhorizon_android.so loader for ARM64.");
        pair("Iniciando cliente em modo offline", "Starting offline client");
        pair("Não foi possível iniciar offline", "Could not start offline");
        pair("Autenticação ainda não configurada", "Authentication not configured yet");
        pair("Não foi possível ler a sessão salva", "Could not read the saved session");
        pair("Iniciando autenticação Microsoft…", "Starting Microsoft authentication…");
        pair("Login Microsoft em andamento", "Microsoft sign-in in progress");
        pair("A autenticação e a checagem de licença estão sendo feitas pelos serviços oficiais.", "Authentication and license checks are performed by official services.");
        pair("Código copiado", "Code copied");
        pair("Conclua o login na página oficial da Microsoft.", "Complete sign-in on Microsoft's official page.");
        pair("Conta Microsoft verificada", "Microsoft account verified");
        pair("A conta e os direitos de Java Edition foram verificados.", "The account and Java Edition entitlements have been verified.");
        pair("Login cancelado", "Sign-in canceled");
        pair("Login não concluído", "Sign-in incomplete");
        pair("Conta removida", "Account removed");
        pair("Falha ao remover conta", "Could not remove account");
        pair("Verificando sessão antes de iniciar", "Verifying session before launch");
        pair("Iniciando cliente Horizon", "Starting Horizon client");
        pair("Não foi possível iniciar o cliente", "Could not start client");
        pair("Sessão não válida", "Invalid session");
        pair("Ação indisponível", "Action unavailable");
        pair("Preparando diagnóstico", "Preparing diagnostics");
        pair("Não foi possível salvar o diagnóstico", "Could not save diagnostics");
        pair("Falha ao preparar o diagnóstico", "Failed to prepare diagnostics");
        pair("Falha ao exportar logs", "Failed to export logs");
        pair("Relatório temporário ausente", "Temporary report missing");
        pair("Salvando diagnóstico", "Saving diagnostics");
        pair("Diagnóstico exportado", "Diagnostics exported");
        pair("O cliente Horizon não iniciou", "The Horizon client did not start");
        pair("Cliente Horizon encerrado", "Horizon client closed");
        pair("Não foi possível ler o resultado do cliente", "Could not read the client result");
        pair("Renderer Vulkan validado", "Vulkan renderer validated");
        pair("Teste do renderer falhou", "Renderer test failed");
        pair("Não foi possível ler o relatório Vulkan", "Could not read the Vulkan report");
        pair("Biblioteca nativa ausente", "Native library missing");
        pair("Arquitetura não suportada", "Unsupported architecture");
        pair("Teste Vulkan iniciado", "Vulkan test started");
        pair("Não foi possível abrir NativeActivity", "Could not open NativeActivity");
        pair("Preparando runtime Horizon", "Preparing Horizon runtime");
        pair("Baixando runtime Horizon", "Downloading Horizon runtime");
        pair("Runtime Horizon pronta", "Horizon runtime ready");
        pair("Runtime instalada e verificada: ", "Runtime installed and verified: ");
        pair("Runtime Horizon indisponível", "Horizon runtime unavailable");
        pair("Runtime pendente", "Runtime pending");
        pair("Botão: ", "Button: ");
        pair("Cor do botão", "Button color");
        pair("Função do botão", "Button action");
        pair("Ativo", "Active");
        pair("Ativar", "Enable");
        pair("Por mundo", "Per world");
        pair("Azul", "Blue");
        pair("Roxo", "Purple");
        pair("Verde", "Green");
        pair("Laranja", "Orange");
        pair("Vermelho", "Red");
        pair("Rosa", "Pink");
        pair("Ciano", "Cyan");
        pair("Cinza", "Gray");
        pair("Clique esquerdo", "Left click");
        pair("Clique direito", "Right click");
        pair("Pular", "Jump");
        pair("Agachar", "Sneak");
        pair("Corrida (Ctrl)", "Sprint (Ctrl)");
        pair("Inventário", "Inventory");
        pair("Soltar item", "Drop item");
        pair("Mão secundária", "Off-hand");
        pair("Atacar", "Attack");
        pair("Usar / colocar", "Use / place");
        pair("Selecionar bloco", "Pick block");
        pair("Perspectiva", "Perspective");
        pair("Retângulo arredondado", "Rounded rectangle");
        pair("Círculo / oval", "Circle / oval");
        pair("Quadrado", "Square");

        // Final pass for launcher progress and diagnostics copy.
        pair("▶  Jogar", "▶  Play");
        pair("Controles", "Controls");
        pair("Os logs podem conter endereços de servidores, nicknames e mensagens. Revise o arquivo antes de compartilhar.", "Logs may contain server addresses, nicknames, and messages. Review the file before sharing.");
        pair("Código: ", "Code: ");
        pair("MOBILEHORIZON  /  RECURSOS", "MOBILEHORIZON  /  RESOURCES");
        pair("Texturas, mods Horizon, data packs e shaders compatíveis. As mudanças entram em vigor ao reiniciar o Minecraft.", "Compatible textures, Horizon mods, data packs, and shaders. Changes take effect after restarting Minecraft.");
        pair("Voltar à tela principal", "Back to main screen");
        pair("Baixe os arquivos oficiais do Minecraft 26.3, prepare as entradas para conversão ou importe uma saída Horizon já preparada.", "Download official Minecraft 26.3 files, prepare inputs for conversion, or import an already-prepared Horizon output.");
        pair("Teste Vulkan terminou sem relatório", "Vulkan test ended without a report");
        pair("A atividade nativa voltou sem gravar o resultado do teste. Isso pode indicar uma falha antes do início do código nativo ou encerramento do processo; ainda não identifica a causa. Confira os registros Android se puder.", "The native activity returned without saving a test result. This may indicate a failure before native code started or that the process ended; it does not identify the cause. Check Android logs if possible.");
        pair("Cliente nativo encerrou sem relatório", "Native client ended without a report");
        pair("A atividade do jogo voltou sem entregar um relatório de inicialização. Isso sugere que o processo nativo terminou antes de registrar o resultado; ainda não é possível atribuir a causa ao Vulkan ou aos recursos do Minecraft.", "The game activity returned without a startup report. The native process may have ended before recording a result; the cause cannot yet be attributed to Vulkan or Minecraft resources.");
        pair("O download completo de assets pode ocupar centenas de MB.", "A full asset download can use hundreds of MB.");
        pair("Este fluxo baixa arquivos oficiais da Mojang, verifica tamanho e SHA-1, mas não autentica uma conta nem concede uma licença. Prossiga apenas se você tiver uma licença válida de Minecraft Java Edition.", "This process downloads official Mojang files and verifies size and SHA-1, but does not authenticate an account or grant a license. Continue only if you have a valid Minecraft Java Edition license.");
        pair("A preparação pode continuar, mas o Android talvez não mostre a notificação na área de notificações. Você pode permitir isso nas configurações do app.", "Preparation can continue, but Android might not show a notification. You can allow notifications in app settings.");
        pair("A conversão pode continuar, mas o progresso e a conclusão talvez não apareçam na área de notificações.", "Conversion can continue, but progress and completion may not appear in notifications.");
        pair("O login pode continuar, mas o Android talvez não mostre a notificação na área de notificações.", "Sign-in can continue, but Android might not show a notification.");
        pair("O download pode continuar em segundo plano, mas o progresso talvez não apareça na área de notificações. Você pode permitir notificações nas configurações do Android.", "The download can continue in the background, but progress may not appear in notifications. You can allow notifications in Android settings.");
        pair("Use de 3 a 16 letras, números ou sublinhados.", "Use 3–16 letters, numbers, or underscores.");
        pair("O token de renovação criptografado e os dados públicos da conta serão removidos deste dispositivo.", "The encrypted refresh token and public account data will be removed from this device.");
        pair("Será criado um ZIP com latest.log, até três logs arquivados recentes, o relatório de falha mais recente e metadados do aparelho/build. O relatório não inclui arquivos de sessão ou tokens de autenticação. O log pode conter o endereço do servidor, nicknames e mensagens de chat; compartilhe somente depois de revisar.", "A ZIP will be created with latest.log, up to three recent archived logs, the latest crash report, and device/build metadata. It excludes session files and authentication tokens. Logs may contain server addresses, nicknames, and chat messages; review them before sharing.");
        pair("O jogo só inicia após a confirmação oficial da conta e da licença.", "The game starts only after the account and license are officially verified.");
        pair("A primeira abertura pode ter pequenas pausas enquanto o renderer inicializa e cria caches. O perfil não autentica uma conta Microsoft nem concede licença online.", "The first launch may pause briefly while the renderer initializes and creates caches. The profile does not authenticate a Microsoft account or grant an online license.");
        pair("O APK precisa conter o carregador nativo libhorizon_android.so para ARM64.", "The APK must include the native libhorizon_android.so loader for ARM64.");
        pair("A sessão oficial foi verificada. A inicialização ainda depende de recursos derivados completos e validação real de gameplay.", "The official session was verified. Startup still requires complete derived resources and real gameplay validation.");
        pair("Este APK foi compilado sem HORIZON_MSA_CLIENT_ID. O mantenedor precisa criar um registro Microsoft próprio com contas pessoais e fluxo de código de dispositivo, depois solicitar aprovação de acesso às APIs do Minecraft. O ID de outro launcher não pode ser reutilizado.", "This APK was built without HORIZON_MSA_CLIENT_ID. The maintainer must create a Microsoft app registration for personal accounts and device-code flow, then request access to Minecraft APIs. Another launcher's ID cannot be reused.");
        pair("O login continuará em segundo plano. O aplicativo salva somente o token de renovação criptografado no Android Keystore.", "Sign-in will continue in the background. The app stores only the encrypted refresh token in Android Keystore.");
        pair("A validação continua em segundo plano para manter a interface responsiva.", "Validation continues in the background to keep the interface responsive.");
        pair("O teste de renderer requer ARM64.", "The renderer test requires ARM64.");
        pair("Este teste cria uma superfície WGPU, solicita um adaptador Vulkan e tenta apresentar um frame de cor simples; não carrega os recursos do Minecraft.", "This test creates a WGPU surface, requests a Vulkan adapter, and tries to present a simple color frame; it does not load Minecraft resources.");
        pair("Verificando a versão Android disponível antes de iniciar o jogo.", "Checking the available Android runtime before starting the game.");
        pair("A primeira instalação exige uma runtime Android ARM64 verificada.", "First install requires a verified Android ARM64 runtime.");
        pair("Runtime instalada e verificada: ", "Runtime installed and verified: ");
        pair("O jogo não será iniciado sem uma runtime Android verificada.", "The game will not start without a verified Android runtime.");
        pair("Nenhuma runtime verificada está instalada; a inicialização do jogo foi bloqueada.", "No verified runtime is installed; game startup was blocked.");
        pair("A sessão será renovada e a licença será checada novamente.", "The session will be renewed and the license checked again.");
        pair("O serviço continuará em segundo plano, com notificação de andamento e conclusão. Esta etapa ainda prepara as entradas vanilla, não os recursos derivados finais do Horizon.", "The service continues in the background with progress and completion notifications. This step prepares vanilla inputs, not final derived Horizon resources.");
        pair("O processo continuará em segundo plano, com notificação de andamento e conclusão. Esta etapa ainda prepara as entradas vanilla, não os recursos derivados finais do Horizon.", "The process continues in the background with progress and completion notifications. This step prepares vanilla inputs, not final derived Horizon resources.");
        pair("A preparação pode continuar, mas o Android talvez não mostre a notificação na área de notificações. Você pode permitir isso nas configurações do app.", "Preparation can continue, but Android may not show notifications. You can allow notifications in app settings.");
        pair("Destino: files/minecraft\nBibliotecas: ", "Destination: files/minecraft\nLibraries: ");
        pair("Plano: .local/horizon-input/26.3/conversion-plan.json", "Plan: .local/horizon-input/26.3/conversion-plan.json");
        pair("Cancelamento solicitado ao login Microsoft…", "Cancellation requested for Microsoft sign-in…");
        pair("Cancelamento solicitado ao serviço. A operação vai parar em um ponto seguro…", "Cancellation requested. The service will stop at a safe point…");
        pair("Cancelamento solicitado. A operação vai parar em um ponto seguro…", "Cancellation requested. The operation will stop at a safe point…");
        pair("Preparando Java 25 ARM64 e iniciando o exportador completo…", "Preparing Java 25 ARM64 and starting the full exporter…");
        pair("Iniciando serviço de download em segundo plano…", "Starting background download service…");
        pair("Iniciando serviço de preparação em segundo plano…", "Starting background preparation service…");
        pair("Saída validada: .local/horizon-client/26.3", "Validated output: .local/horizon-client/26.3");
        pair("Arquivos parciais não verificados serão descartados.", "Unverified partial files will be discarded.");
        // Final pass for dynamic status fragments while preserving file paths and names.
        pair("Destino: .local/horizon-client/", "Destination: .local/horizon-client/");
        pair("\n\nOs arquivos anteriores só foram substituídos depois da conclusão da importação.", "\n\nThe previous files were replaced only after the import completed.");
        pair("A pasta temporária foi descartada. A importação anterior, se existia, não foi substituída.", "The temporary folder was discarded. The previous import, if any, was not replaced.");
        pair("\n\nA importação anterior, se existia, foi mantida.", "\n\nThe previous import, if any, was kept.");
        pair("Destino privado: files/minecraft. Arquivos só são ativados depois da verificação SHA-1 e tamanho.", "Private destination: files/minecraft. Files are activated only after SHA-1 and size checks.");
        pair("O serviço pode continuar mesmo se você sair desta tela. O sistema também mostra uma notificação de progresso.", "The service can continue after you leave this screen. The system also shows a progress notification.");
        pair("Arquivos verificados.", "Files verified.");
        pair("Na primeira execução serão baixados os pacotes do runtime Java 25. Use “Baixar base + todos os assets” antes de começar. A conversão pode levar bastante tempo e precisa de espaço livre.", "The Java 25 runtime packages download on first run. Use “Download base + all assets” first. Conversion can take a while and requires free space.");
        pair("Você pode sair da tela. O serviço continua no primeiro plano e envia uma notificação de progresso.", "You can leave the screen. The foreground service continues and sends progress notifications.");
        pair("\n\nA validação foi feita em segundo plano. Agora teste “Iniciar com conta offline”.", "\n\nValidation happened in the background. Now test “Start with offline account”.");
        pair("Nenhum detalhe do preparador foi retornado.", "The preparer returned no details.");
        pair("Recursos vanilla e exportações diretas preparados — oráculos pendentes", "Vanilla resources and direct exports prepared — oracles pending");
        pair("\n\nForam gerados apenas 3 mapas JSON diretos em .local/horizon-input/26.3/direct-export. Isso ainda não produz .local/horizon-client/26.3 nem torna o jogo jogável. Faltam os oráculos Java 25 e a geração de registries, gameplay, render, colisão e worldgen.", "\n\nOnly 3 direct JSON maps were generated in .local/horizon-input/26.3/direct-export. This does not create .local/horizon-client/26.3 or make the game playable. Java 25 oracles and registry, gameplay, rendering, collision, and world-generation output are still missing.");
        pair("O cliente tentará iniciar com o nome ", "The client will try to start with the name ");
        pair("Perfil local: ", "Local profile: ");
        pair("\n\nA primeira abertura pode ter pequenas pausas enquanto o renderer inicializa e cria caches. O perfil não autentica uma conta Microsoft nem concede licença online.", "\n\nThe first launch may pause briefly while the renderer initializes and creates caches. This profile does not authenticate a Microsoft account or grant an online license.");
        pair("Tamanho: ", "Size: ");
        pair(" · opacidade: ", " · opacity: ");
        pair(" · sensibilidade: ", " · sensitivity: ");
        pair("Não foi possível iniciar o login", "Could not start sign-in");
        pair("Entre novamente pela Microsoft para iniciar o cliente online.", "Sign in with Microsoft again to start the online client.");
        pair("Reunindo registros do cliente e compactando o relatório…", "Collecting client logs and compressing the report…");
        pair("Anexe o ZIP salvo nesta conversa para eu identificar recusas de recursos, falhas de decodificação, componentes de itens descartados e problemas com texturas de NPCs.", "Attach the saved ZIP here so resource rejections, decoding failures, dropped item components, and NPC texture issues can be investigated.");
        pair("\n\nEsse relatório é gerado pelo motor nativo. Um encerramento sem erro ainda não prova que um mundo foi carregado.", "\n\nThis report is generated by the native engine. A clean exit does not prove that a world loaded.");
        pair("\n\nEsse resultado verifica o NativeActivity e a apresentação gráfica; não comprova carregamento de recursos ou gameplay.", "\n\nThis checks NativeActivity and graphics presentation; it does not confirm resource loading or gameplay.");
        pair("Fechar menu", "Close menu");
        pair("Código: ", "Code: ");
        pair("Texturas, mods Horizon, data packs e shaders compatíveis. As mudanças entram em vigor ao reiniciar o Minecraft.", "Compatible textures, Horizon mods, data packs, and shaders. Changes take effect after restarting Minecraft.");
        pair("MOBILEHORIZON  /  RECURSOS", "MOBILEHORIZON  /  RESOURCES");
        pair("Voltar à tela principal", "Back to main screen");
        // Background notification wording.
        pair("Baixando Minecraft ", "Downloading Minecraft ");
        pair("Download concluído", "Download complete");
        pair("Download cancelado", "Download canceled");
        pair("Download não concluído", "Download incomplete");
        pair("Download oficial", "Official download");
        pair("Downloads do Minecraft", "Minecraft downloads");
        pair("Progresso, cancelamento e conclusão dos downloads oficiais de Minecraft.", "Progress, cancellation, and completion of official Minecraft downloads.");
        pair("Preparando recursos para Minecraft 26.3", "Preparing Minecraft 26.3 resources");
        pair("Preparação concluída", "Preparation complete");
        pair("Preparação cancelada", "Preparation canceled");
        pair("Preparação não concluída", "Preparation incomplete");
        pair("Preparação de recursos vanilla", "Vanilla resource preparation");
        pair("Preparação de recursos", "Resource preparation");
        pair("Progresso, cancelamento e conclusão da preparação de recursos vanilla.", "Progress, cancellation, and completion of vanilla resource preparation.");
        // Horizon runtime update card, status, and verification progress.
        pair("Verificando releases e atualizações da runtime Horizon…", "Checking for Horizon runtime releases and updates…");
        pair("Procurando e baixando a runtime Horizon para Android ARM64…", "Looking for and downloading the Horizon runtime for Android ARM64…");
        pair("Teste do atualizador: baixada a versão anterior do Horizon PC ", "Updater test: downloaded the previous Horizon PC version ");
        pair("Consultando o release oficial mais recente do Horizon…", "Checking the latest official Horizon release…");
        pair("Procurando a runtime Android ARM64 mais recente publicada…", "Looking for the latest published Android ARM64 runtime…");
        pair("Release oficial ", "Official release ");
        pair(" detectada, mas a runtime Android ARM64 correspondente ainda não ", " detected, but the matching Android ARM64 runtime has not yet ");
        pair("foi publicada.", "been published.");
        pair("A versão instalada será mantida e o launcher continuará verificando ", "The installed version will be kept while the launcher continues checking ");
        pair("automaticamente até encontrar a runtime exata.", "automatically until the exact runtime is found.");
        pair("detectada, mas nenhum pacote Android ARM64 verificado está disponível. ", "detected, but no verified Android ARM64 package is available. ");
        pair("O launcher continuará verificando automaticamente.", "The launcher will keep checking automatically.");
        pair("Nenhum release Android do Horizon foi encontrado; ", "No Horizon Android release was found; ");
        pair("a consulta à versão oficial também não foi concluída.", "the official release query also did not complete.");
        pair("Horizon está atualizado — release PC ", "Horizon is up to date — PC release ");
        pair("O runtime Android para Horizon PC ", "The Android runtime for Horizon PC ");
        pair(" foi baixado e verificado; será ativado na próxima abertura do jogo.", " was downloaded and verified; it will activate the next time the game opens.");
        pair("O runtime Android instalado corresponde à release PC ", "The installed Android runtime matches PC release ");
        pair(" Não foi possível consultar agora a release oficial mais recente do Horizon.", " The latest official Horizon release could not be checked right now.");
        pair("Esse runtime foi baixado e verificado e será ativado na próxima ", "This runtime was downloaded and verified and will be activated at the next ");
        pair("abertura do jogo. O pacote Android da release oficial mais nova ainda ", "game launch. The Android package for the latest official release is still ");
        pair("não está disponível; o aplicativo continuará verificando automaticamente.", "unavailable; the app will keep checking automatically.");
        pair("O runtime instalado permanece na versão ", "The installed runtime remains on version ");
        pair(" até que um pacote Android correspondente à release ", " until an Android package for release ");
        pair(" seja publicado. O aplicativo continuará verificando automaticamente.", " is published. The app will keep checking automatically.");
        pair("Runtime Android verificado para o release oficial ", "Android runtime verified for official release ");
        pair("; será ativado na próxima inicialização do jogo.", "; it will activate the next time the game starts.");
        pair("Horizon está atualizado — release oficial ", "Horizon is up to date — official release ");
        pair(" e runtime Android ", " and Android runtime ");
        pair(" instalado.", " installed.");
        pair("Baixando manifesto e metadados da runtime…", "Downloading runtime manifest and metadata…");
        pair("Baixando manifesto da runtime…", "Downloading runtime manifest…");
        pair("Validando o manifesto e a versão da runtime…", "Validating the runtime manifest and version…");
        pair("Baixando a runtime Horizon Android ARM64…", "Downloading the Horizon Android ARM64 runtime…");
        pair("Baixando runtime Horizon…", "Downloading Horizon runtime…");
        pair("Download concluído. Instalando a runtime verificada…", "Download complete. Installing the verified runtime…");
        pair("Download concluído. Verificando SHA-256…", "Download complete. Verifying SHA-256…");
        pair("Arquivo baixado e SHA-256 verificado.", "File downloaded and SHA-256 verified.");
        pair("O download excedeu o tamanho esperado.", "Download exceeded the expected size.");
        pair("SHA-256 do asset baixado não confere.", "Downloaded asset SHA-256 does not match.");
        // Last missing fragments discovered while auditing status and diagnostics.
        pair("O serviço concluiu e validou a árvore de recursos.", "The service completed and validated the resource tree.");
        pair("Controles Touch salvos", "Touch controls saved");
        pair("% · sensibilidade: ", "% · sensitivity: ");
        pair("%. Os ajustes serão aplicados na próxima abertura do jogo.", "%. Changes will be applied the next time the game opens.");
        pair("A autenticação não foi concluída.", "Authentication was not completed.");
        pair("\n\nA sessão será renovada e a licença será checada novamente.", "\n\nThe session will be renewed and the license checked again.");
        pair("Copiando o ZIP para o local escolhido…", "Copying the ZIP to the selected location…");
        pair("O Android não abriu o arquivo de destino.", "Android could not open the destination file.");
        pair("Falha ao salvar o diagnóstico", "Failed to save diagnostics");
        pair(". O jogo pode ser iniciado.", ". The game can now be started.");
        pair(". A próxima verificação atualizará para ", ". The next check will update to ");
        pair(" A versão instalada será mantida e o launcher continuará verificando ", " The installed version will be kept while the launcher continues checking ");
        pair(" detectada, mas nenhum pacote Android ARM64 verificado está disponível. ", " detected, but no verified Android ARM64 package is available. ");
        pair("Runtime ativado para a próxima inicialização: PC ", "Runtime activated for the next launch: PC ");
        pair("O Android não abriu o arquivo de destino.", "Android could not open the destination file.");
        pair("Recolher ", "Collapse ");
        pair("Expandir ", "Expand ");
        pair("Preparando leitura do arquivo ZIP…", "Preparing ZIP file for reading…");
        pair("Analisando a pasta selecionada…", "Analyzing selected folder…");
        pair("Arquivos: ", "Files: ");
        pair("Dados copiados: ", "Data copied: ");
        pair("Item: ", "Item: ");
        pair("Contagem: ", "Count: ");
        pair("Arquivo atual: ", "Current file: ");
        pair("Total conhecido: ", "Known total: ");
        pair("Etapa: ", "Stage: ");
        pair("Download", "Download");
        pair("Preparação", "Preparation");
        pair("Login Microsoft", "Microsoft sign-in");
        pair("Conta verificada: ", "Verified account: ");
        pair("\nA licença foi verificada durante o último login.", "\nThe license was verified during the last sign-in.");
        pair("não definido", "not set");
        pair("Destino: files/minecraft\nBibliotecas: ", "Destination: files/minecraft\nLibraries: ");
        pair("\nAssets baixados: ", "\nAssets downloaded: ");
        pair("\nAssets completos: ainda não baixados", "\nAssets complete: not downloaded yet");
        pair("\nSHA-1 do JSON: ", "\nJSON SHA-1: ");
        pair("Padrão", "Default");
        pair("Tecla personalizada", "Custom key");
        pair("Scroll para cima", "Scroll up");
        pair("Scroll para baixo", "Scroll down");
        pair("Texto em fonte Minecraft", "Minecraft-font text");
        pair("Texto do botão", "Button label");
        pair("Atualizar sessão Microsoft", "Refresh Microsoft session");
        pair("Entrar com Microsoft", "Sign in with Microsoft");
        pair("Nenhuma conta verificada neste dispositivo.", "No account has been verified on this device.");
        pair(" ativado", " enabled");
        pair(" desativado", " disabled");
        pair("A mudança será aplicada quando o Minecraft iniciar novamente.", "The change will apply the next time Minecraft starts.");
    }

    private UiLanguage() { }

    private static void pair(String portuguese, String english) {
        PT_TO_EN.put(portuguese, english);
        EN_TO_PT.put(english, portuguese);
    }

    static boolean isEnglish(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String fallback = Locale.getDefault().getLanguage().equalsIgnoreCase("en") ? "en" : "pt";
        return "en".equals(preferences.getString(KEY_LANGUAGE, fallback));
    }

    static String translate(Context context, String value) {
        if (value == null) return null;
        Map<String, String> map = isEnglish(context) ? PT_TO_EN : EN_TO_PT;
        String exact = map.get(value);
        if (exact != null) return exact;
        String bestKey = null;
        String bestValue = null;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key.length() >= 6 && value.startsWith(key)
                    && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
                bestValue = entry.getValue();
            }
        }
        String translated = bestKey == null ? value : bestValue + value.substring(bestKey.length());
        // Translate longer known fragments after dynamic values, preserving counters, paths and names.
        // Small labels are excluded so custom text is not unintentionally changed.
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key.length() >= 12 && translated.contains(key)) {
                translated = translated.replace(key, entry.getValue());
            }
        }
        return translated;
    }

    static String switchLabel(Context context) {
        return "PT ↔ EN";
    }

    static String switchDescription(Context context) {
        return isEnglish(context)
                ? "Interface language: English. Tap to switch to Portuguese."
                : "Idioma da interface: Português. Toque para mudar para English.";
    }

    static void toggle(Activity activity) {
        String next = isEnglish(activity) ? "pt" : "en";
        activity.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, next).apply();
        activity.recreate();
    }
}
