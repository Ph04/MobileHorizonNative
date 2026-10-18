# Roadmap

## Fase 0 — Fonte e critérios
- [x] Criar repositório isolado.
- [x] Registrar direção Android-first e arquitetura da camada de compatibilidade.
- [x] Auditar fonte 0.9.9 e a biblioteca nativa ARM64 (hash, arquitetura e símbolos).
- [x] Fixar o arquivo-fonte antigo e hash da runtime usada no CI.

## Fase 1 — App nativo e importação
- [x] Módulos Gradle/Android e CI.
- [x] UI Android nativa.
- [x] Importação SAF de pasta/ZIP com progresso, cancelamento cooperativo, staging e rollback.
- [x] Verificação de manifesto Horizon/schema/data version e arquivos obrigatórios.
- [x] Limites de segurança para arquivo compactado.
- [x] Testes unitários do núcleo de compatibilidade.
- [x] Compilar APK de depuração no CI e validar que o binário ARM64 está empacotado.
- [ ] Instalar o APK da branch mais recente e testar o fluxo inteiro em Android real.

## Fase 2 — Download e preparação vanilla
- [x] Recuperar o downloader oficial da versão 26.3.
- [x] Verificar URLs HTTPS reconhecidos, manifesto da versão, tamanho e SHA-1 antes de ativar arquivos.
- [x] Baixar/verificar cliente JAR, servidor JAR, asset index e bibliotecas Android/ARM64.
- [x] Oferecer download separado de todos os asset objects com progresso, cancelamento e notificação.
- [x] Verificar integridade SHA-1 dos assets presentes antes de criar o plano.
- [x] Extrair assets/ e data/ opcionais do cliente; localizar data/minecraft tanto na raiz do servidor quanto no JAR interno do bundler.
- [x] Materializar asset objects por seus nomes oficiais somente após validar tamanho e SHA-1; contabilizar assets ausentes em vez de inventá-los.
- [x] Produzir conversion-plan.json com hashes, contagens, ausências de assets, objetos materializados e estado do pipeline.
- [x] Testar os formatos de arquivo de servidor plano/bundler e erro claro quando o JAR interno não contém dados.
- [ ] Portar/empacotar os oráculos de JDK 25 e o exportador real, sem Termux.
- [x] Executar a etapa de entrada vanilla em serviço foreground com progresso/cancelamento/notificação de conclusão.
- [ ] Gerar todos os arquivos derivados exigidos pela runtime Horizon em .local/horizon-client/26.3.
- [ ] Validar manifest, hashes e completude dessa saída antes de ativar a runtime.

## Fase 3 — Runtime gráfica e compatibilidade
- [x] Reutilizar a biblioteca Horizon ARM64 a partir do arquivo-fonte antigo com hash fixado.
- [x] Empacotar libhorizon_android.so no APK de CI e verificar arquitetura/símbolos.
- [x] Expor teste Vulkan isolado e mostrar seu resultado à UI.
- [x] Bloquear o lançamento do cliente enquanto recursos e autenticação oficial não estiverem prontos.
- [ ] Testar NativeActivity, Vulkan, lifecycle e drivers em aparelho ARM64 real.
- [x] Implementar fluxo de código de dispositivo Microsoft/Xbox/XSTS/Minecraft, consulta de perfil e verificação do entitlement de Java Edition.
- [x] Criptografar o token de renovação com Android Keystore e entregar ao jogo apenas sessão temporária.
- [ ] Configurar App ID próprio e obter aprovação dos serviços Minecraft; testar login real no APK publicado pelo CI.
- [ ] Testar o cliente com árvore de recursos derivada completa, autenticação aprovada e controles de gameplay.

## Critérios para declarar jogável
- Build reproduzível e APK com runtime verificada.
- Downloader oficial validado no telefone e arquivos íntegros.
- Exportador Android produz a árvore completa de recursos Horizon.
- Autenticação oficial é concluída e a licença Java é verificada.
- Cliente carrega mundo e recursos num aparelho real; smoke test gráfico sozinho não satisfaz esse critério.
