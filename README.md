# MobileHorizonNative

Aplicativo Android nativo para importar e validar saídas de preparação de recursos, com uma camada de compatibilidade desacoplada do backend Horizon.

> Estado atual: primeiro marco do app nativo. A importação de pasta/ZIP e o relatório de compatibilidade estão implementados no código. O build Gradle/Android ainda precisa ser confirmado pela execução da CI; o preparador de recursos nativo e o motor Horizon não estão incluídos nesta versão inicial.

## O que já existe

- **UI Android nativa em Java**, sem WebView, com uma tela principal e duas ações essenciais: importar uma pasta preparada ou importar um ZIP.
- **Importação por Storage Access Framework (SAF)**, sem pedir permissão ampla para ler o armazenamento.
- **Progresso visível** durante a análise/cópia, com contagem de arquivos e bytes, além de cancelamento cooperativo.
- **Importação transacional**: os dados vão para uma pasta temporária do app e só substituem o conjunto ativo depois de terminar e passar pela validação básica. Em caso de erro, a importação anterior é preservada.
- **Proteções para arquivos ZIP**: caminhos absolutos e .. são recusados; caminhos duplicados, profundidade excessiva, mais de 20.000 itens e mais de 2 GiB descompactados também são recusados.
- **Camada de compatibilidade Kotlin** com SDK mínimo, ABI, capacidades exigidas e validação do formato do SHA-256 declarado pelo backend.
- **Testes unitários do núcleo** para combinações compatíveis/incompatíveis e requisitos do backend.
- **GitHub Actions** para executar os testes e tentar compilar um APK de depuração, publicado como artifact da execução.

## Compilar

Requisitos: JDK 17, Android SDK 35 e Gradle 8.9.

Na raiz:

~~~bash
gradle --no-daemon test :app:assembleDebug
~~~

O APK de depuração fica em app/build/outputs/apk/debug/. No GitHub, abra a execução do workflow **Android build and compatibility tests** e baixe o artifact mobilehorizon-native-debug quando o job passar.

## Importar uma saída já preparada

1. Instale o APK de teste e toque em **Selecionar pasta preparada** ou **Selecionar arquivo ZIP**.
2. Escolha a saída gerada anteriormente.
3. Acompanhe a fase atual, os arquivos/bytes processados e a barra de progresso (quando o tamanho da origem for conhecido).
4. Se já houver uma importação ativa, confirme a substituição. A nova cópia só passa a ser ativa depois do término da operação e da validação básica.

A saída ativa fica no armazenamento privado do app, em files/prepared-resources. A UI informa se foi encontrado um horizon-resources.json na raiz. Se não houver manifesto, a importação genérica ainda pode ser concluída, mas não é marcada como compatível com um backend específico.

## O que ainda não faz

- Ainda **não prepara localmente** toda a árvore Horizon que hoje é criada pelo horizon-prepare fora do app.
- Não inclui a biblioteca libhorizon_android.so nem inicia o motor gráfico nesta versão. O adaptador Horizon deve permanecer desativado até que a ABI, o SHA-256 e o carregamento real no aparelho sejam verificados.
- Uma importação concluída comprova apenas que os arquivos foram copiados e passaram pelas verificações básicas; não prova que Minecraft inicia, carrega um mundo ou funciona com Vulkan.
- O build APK não foi confirmado neste ambiente de edição. O workflow CI foi adicionado para executar essa validação.

## Fonte 0.9.9 anterior

O pacote recebido foi analisado e documentado em Auditoria da fonte 0.9.9 (docs/source-audit-0.9.9.md). O projeto anterior combina UI, download da versão 26.3, validação e caminhos específicos do Horizon. O objetivo desta base é reutilizar as ideias seguras de importação sem manter o acoplamento total ao Horizon ou exigir Termux.

## Organização

- app/: UI Android e importação SAF/ZIP.
- core/: modelos e verificações de compatibilidade, sem dependência direta de classes Android.
- docs/architecture.md: fluxo e limites entre UI, importador e backends.
- docs/roadmap.md: próximos marcos de desenvolvimento.
- docs/source-audit-0.9.9.md: achados da fonte e da biblioteca nativa recebida.
