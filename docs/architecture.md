# Arquitetura Android-first

## Fluxo do launcher

O fluxo em implementação é:

`UI → OfficialDownloadService → verificação de integridade → área de trabalho vanilla → exportador Horizon → validação da saída → importador/runtime`

O cliente gráfico é um componente separado:

`UI → verificação de runtime ARM64 → NativeActivity → horizon-android → horizon-client`

O teste Vulkan tem uma rota própria e não precisa da árvore completa de recursos. O lançamento normal do cliente ainda exige a saída Horizon completa e autenticação Microsoft/Minecraft com entitlement de Java Edition verificado; a interface bloqueia esse lançamento até que ambas estejam implementadas.

## 1. Downloader oficial

`OfficialMinecraftInstaller` consulta o manifesto Mojang via HTTPS e exige a versão fixada 26.3, Java major 25, hashes de manifesto aceitos e SHA-1 fixados para os JARs de cliente/servidor. Para artefatos de bibliotecas, usa tamanhos e SHA-1 do manifesto e filtra os artefatos descritos para as capacidades Android/Linux ARM64. Arquivos são baixados para `.part`; só são promovidos ao caminho ativo após tamanho e hash passarem.

O download base inclui manifesto JSON, client JAR, server JAR, índice de assets e bibliotecas aplicáveis. O download completo de asset objects é separado; deduplica por hash, limita o total anunciado, baixa em três workers e mantém progresso agregado por bytes processados. Todos os URLs de download são limitados a HTTPS e a domínios Mojang/Minecraft reconhecidos.

`OfficialDownloadService` mantém a operação em primeiro plano com notificação. Progresso e conclusão são enviados à UI; a última conclusão não reconhecida é mantida brevemente em preferências para que a interface a recupere ao voltar, depois é reconhecida pelo launcher.

## 2. Entrada para conversão

`HorizonResourceConverter.prepareConversionInputs` valida o manifesto oficial, cliente/servidor, bibliotecas, índice e hashes dos asset objects presentes. Asset objects ausentes são reportados no plano, não tratados como se já tivessem sido baixados.

Em seguida, o app extrai recursos para staging:

- Os arquivos `assets/` e `data/` presentes no JAR do cliente, preservando seus caminhos relativos.
- `data/minecraft/` do `server.jar` se estiver na raiz; caso contrário, procura o JAR interno em `META-INF/versions/<versão>/server-<versão>.jar` e extrai dali. Essa distinção é necessária para o formato bundler do servidor.
- Os asset objects oficiais são materializados sob seus caminhos de recurso quando estão presentes e seu SHA-1 foi verificado. Como `assets/` pode ser fornecido pelo índice externo em vez do JAR, a existência de `minecraft/lang/en_us.json` é validada depois dessa etapa. A árvore `data/` do client JAR é opcional.

A extração usa limites de entradas/arquivos/tamanho, rejeita caminhos inseguros, valida arquivos vanilla essenciais, registra a origem dos dados do servidor no manifesto e promove a árvore como uma unidade. Há testes unitários para layouts flat e bundler com JAR interno. `source-input-manifest.json` registra hashes dos JARs, contagens e o estado explícito `VANILLA_INPUTS_ONLY_NOT_HORIZON_RUNTIME_RESOURCES`. O plano `conversion-plan.json` aponta para os JARs, bibliotecas, assets e árvore extraída.

**Isso ainda não é a conversão final.** O preparador upstream usa JDK 25 para compilar/executar numerosos oráculos Java que geram facts, registries, tabelas de gameplay e dados worldgen. O novo app ainda não executa esses oráculos; por isso não sintetiza um `horizon-resources.json` falso nem ativa uma saída incompleta.

## 3. Validação e importação da runtime

A saída aceita pelo importador é `.local/horizon-client/26.3`. O importador SAF aceita uma pasta ou ZIP, extrai/copia para staging e valida um manifesto com `schema=7`, `data_version=5023`, contador de arquivos e arquivos-chave derivados. A árvore incompleta não substitui a anterior.

A biblioteca nativa usada por NativeActivity não está marcada no código como “compatível” só por existir. O CI baixa a fonte antiga a partir de um arquivo com SHA-256 fixado, valida o SHA-256 e os símbolos do `libhorizon_android.so`, verifica AArch64 e empacota a biblioteca no APK. Esse teste é estrutural e de empacotamento, não comprova inicialização no telefone.

## 4. Renderer e diagnóstico

O marcador `horizon-native-smoke.flag` faz a ponte NativeActivity executar o smoke test Winit/WGPU/Vulkan em vez do cliente. O resultado `horizon-native-smoke-result.txt` é lido quando a UI retorna, para distinguir PASS/FAIL. O smoke test apresenta uma cor sólida em um frame; não valida carregamento de Minecraft, mundo, textura, input ou FPS de gameplay.

## 5. Camada de compatibilidade

O módulo `core` contém:

- `DeviceCapabilities`: SDK, ABIs, SAF e memória reportada;
- `BackendDescriptor`: identidade, versão, ABIs, SDK mínimo, capacidades e SHA-256 declarado;
- `CompatibilityReport`: status e problemas codificados;
- `PreparationBackend`: interface para o futuro backend Android de preparação;
- `PreparationProgress` e `PreparationResult`: mensagens estruturadas.

O verificador só bloqueia SAF quando o backend declara `IMPORT_OUTPUT`, exige hash com formato SHA-256 no adaptador Horizon e não trata SDK/ABI como prova de desempenho.

## 6. Limites ainda em aberto

- Portar o exportador Horizon de forma executável no Android sem Termux.
- Integrar autenticação Microsoft/Minecraft oficial e verificar a posse de Java Edition.
- Testar o binário ARM64 em dispositivo real e validar logs/lifecycle.
- Validar que o jogo abre, carrega recursos, gera mundo e aceita controles.
