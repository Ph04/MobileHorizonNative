# MobileHorizonNative

**Launcher nativo para Android baseado no Horizon**, o projeto open source que reimplementa Minecraft: Java Edition 26.3 em Rust. O MobileHorizonNative adapta a runtime ao Android e reúne a interface da launcher, preparação de recursos, controles táteis, perfis e ferramentas de diagnóstico em um único aplicativo.

> **Release de referência: build 873.** Esta é a versão que o mantenedor confirmou que abre corretamente no dispositivo. O port Android continua em desenvolvimento: o fato de a launcher iniciar não significa que todos os dispositivos, recursos de servidores ou cenários de jogo estejam totalmente validados.

**[⬇️ Baixar MobileHorizonNative — build 873](https://github.com/Ph04/MobileHorizonNative/releases/tag/mobilehorizonnative-build-873)** · [Código-fonte](https://github.com/Ph04/MobileHorizonNative) · [Horizon original](https://github.com/ludovic111/horizon)

## O que o projeto oferece

### Launcher e experiência Android
- Interface Android para preparar, importar e iniciar o cliente nativo Horizon.
- Layout adaptável para telas em retrato e paisagem, com estado de preparação, progresso e mensagens de diagnóstico.
- Controles táteis sobre o jogo, tradução de toques para entrada do cliente e editor para posicionar/ajustar os botões.
- Perfil local com nick offline para servidores que aceitam conexões em modo offline. Isso não concede autenticação oficial nem contorna servidores que exigem conta online.
- Fluxo de entrada por código de dispositivo Microsoft, armazenamento protegido do token de renovação pelo Android Keystore e verificação dos direitos quando o fluxo de autenticação está configurado. O login depende do App ID do projeto e das aprovações necessárias.

### Downloads e preparação dos arquivos de Minecraft
- Download do manifesto da versão, JARs do cliente/servidor, bibliotecas e índice oficial de assets para Minecraft Java 26.3.
- Verificação de tamanho e hashes dos arquivos antes de aceitá-los.
- Download opcional de todos os assets, com progresso, cancelamento e avisos de conclusão.
- Preparação e inspeção das entradas vanilla, extração dos dados disponíveis e geração de um plano que identifica recursos válidos, ausentes e ainda pendentes.
- Importação de uma pasta ou ZIP de recursos preparados, com validação do manifesto, área temporária, limites de extração e recuperação em caso de erro. A importação não confunde entradas vanilla com uma conversão completa do Horizon.

### Runtime nativa e diagnóstico
- Runtime Horizon compilada para Android ARM64 e integrada à NativeActivity.
- Teste gráfico Vulkan/WGPU para verificar inicialização da superfície nativa. Um teste aprovado confirma a apresentação de um frame, não a compatibilidade completa do jogo.
- Verificações de compatibilidade, registros locais e exportação de diagnósticos para investigar problemas.
- Verificação e instalação versionada de atualizações da runtime do Horizon, mantendo separadas as versões do motor e o APK da launcher.
- Perfil inicial conservador de configurações gráficas para celulares menos potentes. Ele reduz algumas opções visuais e distâncias por padrão, preservando as alterações do jogador depois da primeira aplicação.

## Estado atual e limitações conhecidas

O foco do projeto é tornar o Horizon utilizável como cliente nativo Android. Alguns subsistemas já estão integrados, mas a compatibilidade não deve ser tratada como completa.

- A build 873 é a referência de funcionamento para abertura da aplicação; cada dispositivo e driver Android ainda precisa ser testado.
- A compatibilidade visual com recursos enviados por servidores continua em desenvolvimento. Texturas/modelos personalizados, molduras de itens e algumas entidades de texto ou hologramas podem não ser exibidos corretamente em todas as situações.
- A autenticação e a entrada em servidores dependem da configuração do servidor e da sessão disponível. Um perfil offline só funciona onde o servidor permite modo offline.
- A preparação de recursos e a conversão final ainda têm etapas que precisam de validação e cobertura adicional. O teste Vulkan sozinho não prova que o jogo esteja totalmente pronto.
- Mods Java/Fabric não são carregados simplesmente por instalar esta launcher; não anunciamos compatibilidade universal com mods.

Relate falhas incluindo modelo do aparelho, versão do Android, build utilizada e os registros disponíveis. Evite compartilhar tokens, arquivos privados de sessão ou outros dados de autenticação.

## Como testar a build 873

1. Baixe o APK em [Releases](https://github.com/Ph04/MobileHorizonNative/releases/tag/mobilehorizonnative-build-873) e instale-o no Android.
2. Na launcher, use as opções de download para obter os arquivos oficiais de Minecraft Java 26.3 e, quando necessário, os assets adicionais.
3. Execute a preparação vanilla ou importe uma saída Horizon já preparada, validando o manifesto antes de iniciar o cliente.
4. Configure os controles táteis pelo editor e use o diagnóstico Vulkan para verificar a inicialização gráfica.
5. Para autenticação Microsoft, é necessário compilar/configurar o App ID próprio do projeto e obter as aprovações exigidas. Não compartilhe credenciais ou tokens.

Os arquivos do jogo obtidos pela launcher continuam sujeitos aos termos e direitos da edição Java. O APK do MobileHorizonNative não concede uma licença de Minecraft.

## Desenvolvimento

O repositório contém a launcher Android, a camada de compatibilidade, os componentes de importação e preparação, o bootstrap/integração nativa, testes automatizados e scripts que adaptam a fonte do Horizon ao Android.

- `app/` — interface, controles táteis, perfis, download, importação, autenticação e inicialização.
- `core/` — modelos e verificações de compatibilidade.
- `android-native/` e `android-bootstrap/` — integração da runtime nativa.
- `tools/horizon_patches/` — patches ordenados aplicados à fonte Horizon para compatibilidade Android.
- `docs/` — arquitetura, configuração e notas técnicas.
- [GitHub Actions](https://github.com/Ph04/MobileHorizonNative/actions) — compilação e verificações automatizadas.

Com o ambiente Android/Gradle instalado, o build local começa com:

```bash
gradle --no-daemon test :app:assembleDebug
```

A compilação local pode não incluir automaticamente todos os componentes nativos que o workflow prepara. Consulte os workflows e a documentação antes de comparar APKs de fontes diferentes.

## Créditos e projetos relacionados

### Horizon — projeto original

O motor usado por este port deriva do **[Horizon](https://github.com/ludovic111/horizon)**, projeto de código aberto de **[Ludovic Marie (@ludovic111)](https://github.com/ludovic111)** e colaboradores. O Horizon reimplementa Minecraft: Java Edition 26.3 em Rust; este repositório mantém a launcher/integração Android e os patches específicos necessários ao port.

**Todos os créditos pelo projeto Horizon original, sua arquitetura e seu código-fonte original pertencem ao autor e aos colaboradores do Horizon.** Consulte o [repositório upstream](https://github.com/ludovic111/horizon) e a [licença original](https://github.com/ludovic111/horizon/blob/main/LICENSE) para os termos aplicáveis ao código do Horizon. As alterações específicas para Android e os componentes próprios do MobileHorizonNative são mantidos neste repositório.

### Minecraft e marcas

MobileHorizonNative é um projeto independente e não oficial. **Não é afiliado, aprovado nem mantido pela Mojang Studios ou pela Microsoft.** Minecraft é uma marca de seus respectivos titulares; os arquivos e direitos de Minecraft continuam sujeitos aos termos oficiais.

Obrigado ao projeto Horizon e a seus contribuidores por tornar possível trabalhar nesta adaptação para Android.
