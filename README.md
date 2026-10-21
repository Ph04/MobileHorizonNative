# MobileHorizonNative

Launcher Android-first para preparar entradas de Minecraft Java 26.3, gerenciar sessões oficiais e iniciar a runtime nativa quando todos os pré-requisitos existem. A camada de compatibilidade mantém o motor isolado da UI e impede que uma cópia de arquivos seja confundida com uma instalação jogável.

> **Estado real da versão 0.3.0:** download e extração vanilla com integridade verificada; a preparação agora roda em serviço foreground com notificação de progresso/fim; também há fluxo de código de dispositivo Microsoft, checagem dos direitos Java, token de renovação criptografado e ponte de sessão para a NativeActivity. O exportador que produz os recursos derivados de gameplay/worldgen continua pendente; portanto, o cliente real ainda não é considerado jogável. Builds sem o App ID próprio não podem concluir o login.

## O que já funciona no código

- **Download dos arquivos base oficiais** de Minecraft 26.3: manifesto da versão, cliente JAR, servidor JAR, índice de assets e bibliotecas aplicáveis ao Android. O código verifica tamanho e SHA-1 a partir dos metadados oficiais antes de ativar os arquivos.
- **Download opcional de todos os asset objects**, com três transferências paralelas, deduplicação por hash, progresso por bytes/itens e cancelamento.
- **Serviços Android em primeiro plano** para downloads e preparação vanilla, com notificação de andamento, cancelamento, resultado final recuperável e notificação explícita “Download concluído”.
- **Preflight de conversão**: verifica manifesto, hashes dos JARs, bibliotecas e índice de assets; também confere por SHA-1 cada asset object presente.
- **Primeiro estágio do exportador Android**: confere paridade entre cliente/servidor e gera `block-tags.json`, `damage-types.json` e `damage-tags.json` em `.local/horizon-input/26.3/direct-export`, preservando o texto JSON original. A saída tem seu próprio manifesto e permanece separada da runtime; não representa ainda as tabelas derivados por oráculos.
- **Extração e materialização de entrada vanilla**: tenta obter `data/minecraft/` diretamente do `server.jar` e, quando ele é um bundler, abre também o JAR interno `META-INF/versions/<versão>/server-<versão>.jar`. Os recursos `assets/` e `data/` presentes no JAR do cliente são extraídos quando existem; os assets oficiais externos são materializados pelos nomes do índice e só depois de validar o SHA-1. `data/` do cliente é opcional, e o idioma `minecraft/lang/en_us.json` é validado após a materialização. O processamento usa staging, limites e rollback; assets realmente ausentes continuam explícitos no erro/plano, não são inventados.
- **Plano de conversão** em `.local/horizon-input/26.3/conversion-plan.json`, acompanhado de `.local/horizon-input/26.3/vanilla/source-input-manifest.json`. O plano registra bibliotecas verificadas, asset objects íntegros/ausentes, nomes de assets materializados e bytes gravados. O estado deixa explícito que essa é uma entrada vanilla, não a saída final do Horizon.
- **Importação SAF de pasta ou ZIP** para `.local/horizon-client/26.3`. Exige manifesto compatível (`schema=7`, `data_version=5023`) e arquivos essenciais, usa staging e mantém rollback quando a importação falha. Limites atuais: 250.000 entradas e 2 GiB descompactados.
- **Renderer nativo ARM64** empacotado pelo workflow: o CI recupera a biblioteca pré-compilada da fonte anterior, confere o SHA-256 e os símbolos esperados antes de incluir `libhorizon_android.so) no APK.
- **Testes do layout do servidor**: JARs de teste verificam as duas estruturas suportadas (plana e bundler com JAR interno), além de um erro de diagnóstico quando não há dados de jogo no arquivo.
- **Autenticação Microsoft/Minecraft**: fluxo de código de dispositivo, troca Microsoft → Xbox Live → XSTS → token Minecraft, verificação de `entitlements/mcstore` e perfil, sem modo de contornar posse. O token de renovação é criptografado via Android Keystore; o token de acesso é entregue ao motor por um arquivo de sessão de uso único, modo 0600, removido pelo próprio cliente ao ler. O fluxo requer o `HORIZON_MSA_CLIENT_ID` próprio do projeto e aprovação do App ID para as APIs de Minecraft.
- **Ponte de inicialização nativa**: atualiza a sessão, volta a verificar os direitos, gera a sessão temporária e define `HORIZON_SESSION_FILE` antes de abrir `NativeActivity`; o resultado de inicialização da runtime é mostrado na tela.
- **Teste Vulkan isolado**: abre NativeActivity e verifica inicialização de Winit/WGPU/Vulkan e apresentação de um frame. O resultado é exibido de volta na interface. Esse teste não é um teste de Minecraft ou de gameplay.
- **Camada de compatibilidade Kotlin**, testes unitários e GitHub Actions para compilar um APK de depuração.

## Compilar e obter o APK de teste

O workflow **Android build, official downloader and Horizon runtime** configura JDK 17, Android SDK 35 e Gradle 8.9, injeta o runtime ARM64 verificado, executa testes e compila o APK. O App ID é passado por meio da variável de repositório `HORIZON_MSA_CLIENT_ID`; se ela não existir, o APK continua compilável, mas mostra que o login não foi configurado. Depois de uma execução bem-sucedida, baixe o artifact `mobilehorizon-native-download1-debug`. APK de depuração: apenas para testes.

Com o ambiente Gradle/Android instalado, o núcleo e o APK também podem ser compilados localmente:

~~~bash
gradle --no-daemon test :app:assembleDebug
~~~

O comando local não injeta automaticamente o runtime nativo que o CI prepara. Para autenticação, crie seu próprio App Registration Microsoft e compile com `-PHORIZON_MSA_CLIENT_ID=<seu-client-id>`; nunca reutilize ID do launcher oficial ou de terceiros. O responsável pelo projeto também precisa solicitar a aprovação do App ID nos serviços de Minecraft, conforme `docs/microsoft-account.md`.

## Como testar o marco atual

1. Instale o APK de teste. Ele usa um identificador de aplicação separado para não substituir os dados privados dos APKs anteriores.
2. Toque em **Baixar arquivos base oficiais** para baixar manifesto, JARs, índice e bibliotecas. É possível escolher separadamente **Baixar base + todos os assets**, o que pode transferir centenas de MB.
3. Toque em **Preparar recursos vanilla para conversão**. A tarefa continua em segundo plano e mostra uma notificação de progresso/fim. Ela valida entradas e extrai as árvores vanilla para `.local/horizon-input/26.3/vanilla`; o plano registra bibliotecas verificadas, assets ausentes e arquivos extraídos.
4. Para testar o renderer, toque em **Testar renderer Vulkan**. Um resultado PASS significa que a superfície gráfica foi inicializada e apresentou um frame; não significa que o jogo carregou.
5. Para testar o login, use **Entrar com Microsoft**. É preciso que o APK tenha sido compilado com o App ID do projeto já aprovado pelos serviços do Minecraft. A licença Java Edition é verificada online e a sessão não é armazenada em texto simples.
6. Para importar uma saída já preparada, selecione a pasta final que contém `horizon-resources.json` na raiz, ou um ZIP dessa pasta. O conteúdo será validado e instalado em `.local/horizon-client/26.3`.

Os downloads obtêm arquivos oficiais, mas não concedem licença. O fluxo de login verifica os direitos reais da conta. O App ID próprio e sua aprovação são pré-requisitos de implantação.

## O que ainda falta para jogar

- **Completar o exportador Horizon dentro do Android.** O primeiro exportador direto já cobre mapas JSON que não exigem Java, mas `horizon-prepare` ainda precisa dos oráculos de JDK 25 (`javac`/`java`) para facts por estado de bloco, renderização/colisão, comportamentos, registries, relatórios vanilla e gameplay/worldgen. Ainda não produzimos o conjunto runtime exigido pelo cliente.
- **Testar o renderer num dispositivo ARM64 real.** O CI confirma que a biblioteca tem a arquitetura esperada e que está empacotada; somente um teste em aparelho confirma carregamento, superfície gráfica, drivers e comportamento.
- **Aprovar o App ID Microsoft/Minecraft do projeto.** O código de autenticação está implementado, mas um ID não configurado ou não aprovado será recusado pelos serviços oficiais. A interface não falsifica sessão nem contorna licença.
- **Validar uma execução real com recursos completos**, depois da conversão e autenticação, antes de chamar o launcher de jogável.

## Organização do repositório

- `app/`: UI nativa, downloader, serviços de preparação em segundo plano, autenticação oficial, armazenamento seguro de sessão, importação e inicialização do cliente.
- `core/`: modelos e regras da camada de compatibilidade em Kotlin.
- `docs/architecture.md`: fluxo do downloader à runtime.
- `docs/roadmap.md`: estado de cada marco.
- `docs/source-audit-0.9.9.md`: auditoria da fonte antiga e do binário ARM64.


## Configurar uma assinatura persistente pelo Termux

Sem a chave persistente, os APKs de teste são assinados por uma chave de CI temporária. O Android pode recusar uma instalação sobre uma build anterior. Para configurar uma chave privada estável sem PC:

1. No Termux, instale os requisitos e autentique o GitHub CLI:
   ```bash
   pkg update
   pkg install openjdk-17 gh coreutils
   gh auth login
   ```
2. Baixe e execute o script de configuração deste repositório:
   ```bash
   mkdir -p ~/MobileHorizonNative-setup
   cd ~/MobileHorizonNative-setup
   curl -fL https://raw.githubusercontent.com/Ph04/MobileHorizonNative/feature/android-native-import/tools/setup_android_signing_termux.sh -o setup_android_signing_termux.sh
   bash setup_android_signing_termux.sh
   ```
3. O script cria a keystore no diretório privado `~/.mobilehorizon-signing`, cadastra os quatro GitHub Actions secrets e dispara uma compilação assinada. Acompanhe-a em [GitHub Actions](https://github.com/Ph04/MobileHorizonNative/actions).

**Importante:** a keystore nunca deve ser enviada ao repositório, compartilhada em chats nem enviada como artifact público. Faça uma cópia segura da pasta `~/.mobilehorizon-signing`. A primeira APK com assinatura estável terá o pacote `dev.mobilehorizon.app.offline1`, diferente dos pacotes de teste antigos; ela poderá ser instalada ao lado deles. As atualizações seguintes assinadas com a mesma chave e o mesmo pacote serão instaláveis normalmente. Não desinstale a aplicação antiga antes de confirmar que a nova abre e que os recursos necessários estão acessíveis.
