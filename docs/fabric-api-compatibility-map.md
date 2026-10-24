# Mapa de compatibilidade: Fabric API 26.3 → Horizon

## Objetivo e escopo

Este documento transforma a API publicada do Fabric para Minecraft 26.3 em uma lista de trabalho para adaptar funcionalidades ao sistema de mods nativo do Horizon. O inventário de módulos é baseado no arquivo oficial de inclusão de projetos do ramo 26.3 de FabricMC/fabric-api: https://github.com/FabricMC/fabric-api/blob/26.3/settings.gradle. Não é uma promessa de que todos os mods Java poderão ser executados. Os artefatos fabric-api-bom e fabric-api-catalog são usados para dependências/catálogo e não são módulos de API em si; por isso ficam fora da tabela.

**Catálogo de auditoria atualizado:** `tools/fabric_api_26_3_catalog.json` contém o mapeamento de 46 módulos e 415 classes públicas de fonte da Fabric API 0.165.0+26.3, fixado no commit [2065040b](https://github.com/FabricMC/fabric-api/commit/2065040b236a0d5ac011bd6a45625dc49579b222). O espelho Android é `app/src/main/assets/fabric-api-26.3-catalog.json`; o CI testa que os arquivos são idênticos. A matriz abaixo é um plano de implementação, enquanto o catálogo é a fonte de verdade para classificar símbolos descobertos em JARs.

**Regra de arquitetura:** o Horizon é uma reimplementação em Rust. O runtime de mods do Horizon recebe componentes WebAssembly (.hmod); ele não fornece a JVM nem as classes Java de Minecraft contra as quais um mod Fabric foi compilado. Portanto, o alvo realista é implementar APIs nativas equivalentes no SDK Horizon e portar o comportamento do mod para elas. Copiar arquivos assets/data de um JAR é conversão de conteúdo, não tradução do código Java.

## Resumo do que já existe

Base examinada no commit upstream fixado pelo projeto Android, `1ec2762fe6977e6d5cea8ba7bfed9eb471616f54`.

| Área Horizon | Suporte constatado no SDK/runtime | Limite atual |
|---|---|---|
| Log | Interface de log por mod | Não substitui logger Java nem integração Fabric Loader |
| Estado do jogador | Leitura de posição, yaw/pitch, vida, fome e dimensão | Leitura limitada a um snapshot; não é acesso arbitrário a entidades/mundo |
| Relógio | Tempo de jogo e tempo do dia | Sem API geral de mundo/clima/bioma ao redor |
| Chat | Mostrar mensagem local; evento de chat recebido | Não cobre todo o ciclo de mensagens assinadas, interceptação e envio de rede do Fabric |
| HUD | Painel de linhas de texto por canto | Não equivale à API completa de desenho 2D/3D do Fabric |
| Ciclo de vida | Inicialização, tick, frame, chat e comandos declarados na inicialização | Sem todo o catálogo de callbacks Fabric nem os objetos Java originais |
| Comandos | Comandos próprios do mod, nome e argumentos em texto simples | Não fornece a API Brigadier integral de Minecraft/Fabric ou todas as permissões |
| Armazenamento | Chave/valor por mod, com limite e isolamento | Não é acesso arbitrário ao filesystem |
| Conteúdo | Pacote .hmod pode conter assets/data; declarações Horizon de blocos/itens por modelos/templates vanilla | Não cria automaticamente o comportamento Java, entidades personalizadas, serializers ou registries arbitrários |
| Sandbox | Permissões declaradas por capability, limite de memória/execução e sem WASI | Não executar JARs, classes, entrypoints, Mixins ou access wideners |

A referência de implementação é o contrato WIT em https://github.com/ludovic111/horizon/blob/1ec2762fe6977e6d5cea8ba7bfed9eb471616f54/crates/horizon-mod-sdk/wit/horizon.wit e a descrição em https://github.com/ludovic111/horizon/blob/1ec2762fe6977e6d5cea8ba7bfed9eb471616f54/docs/mods.md.

## Legenda de compatibilidade

- **Conteúdo, condicional:** os arquivos podem ser carregados/copiados; cada tipo de JSON e asset ainda precisa passar por teste de leitura e jogo real.
- **Parcial no SDK:** há uma primitive Horizon parecida, mas falta cobrir a semântica, dados ou callbacks do Fabric.
- **Adaptador novo:** é uma capability/API que deve ser adicionada ao WIT, runtime Rust e SDK de autoria.
- **Port nativo / alta complexidade:** exige comportamento do jogo, renderer, registries, lado servidor ou protocolo que não existe na API de mods atual.
- **Build/dev:** ferramenta para desenvolvimento/diagnóstico; normalmente não é API necessária em tempo de jogo.
- **Sem tradução automática de Java:** Mixins, bytecode e chamadas diretas a classes de Minecraft exigem port do comportamento, não conversão mecânica.

## Inventário completo dos módulos incluídos no Fabric API 26.3

A lista abaixo cobre todos os projetos de API incluídos pelo settings.gradle oficial do ramo 26.3, incluindo o agrupamento de APIs depreciadas. Os resumos descrevem a responsabilidade principal do módulo; a lista de classes e métodos exata deve ser congelada a partir do JAR de API 26.3 utilizado para auditar cada mod.

### Base, leitura, conteúdo e registries

| Módulo Fabric | Responsabilidade principal | Estado Horizon e adaptação necessária |
|---|---|---|
| fabric-api-base | Primitivas comuns e infraestrutura de eventos (Event/EventFactory) usada por outros módulos | **Adaptador novo.** Criar um bus de eventos tipados do Horizon; não replicar objetos Java. Mapear cada evento usado pelo mod para hook nativo. |
| fabric-api-lookup-api-v1 | Registrar/consultar APIs associadas a blocos e stacks, permitindo que módulos conversem sem acoplamento direto | **Adaptador novo.** Criar registro de providers tipados, com identificadores estáveis e leitura segura; depende de block/entity/inventory capabilities. |
| fabric-advancement-api-v1 | Consultar e reagir a progresso/atualizações de advancements | **Parcial/port nativo.** Expor eventos e dados de advancements quando o estado e o sistema de critérios do Horizon os suportarem; escrita/recompensas exigem validação no servidor local. |
| fabric-biome-api-v1 | Hooks de geração/seleção de biomas e acesso à lógica de biomas | **Port nativo.** Precisa de API de bioma e geração; leitura de bioma próximo também depende de world-read. |
| fabric-block-api-v1 | Hooks para comportamento e eventos de blocos: colocação, quebra, interação, vizinhos e ações relacionadas | **Port nativo.** Separar eventos observacionais de mutações; incluir contexto, estado, posição, jogador e resultado cancelável apenas onde a semântica do Horizon permite. |
| fabric-block-getter-api-v2 | Extensões/consultas de acesso a blocos e estados de bloco | **Adaptador novo.** Expor leitura de estado/posição e limites de consulta; não permitir referências internas persistentes ao mundo. |
| fabric-content-registries-v0 | Registries de conteúdo e objetos usados por blocos, itens, entidades e outros sistemas | **Port nativo/alta complexidade.** O conteúdo Horizon atual declara blocos e itens baseados em templates vanilla; ainda não equivale a registries Java genéricos. |
| fabric-convention-tags-v2 | Convenções de tags compartilhadas entre mods, principalmente o namespace c | **Conteúdo, condicional.** Preservar tags de convenção em data/ e validar que Horizon as lê e que o tipo de tag é implementado. |
| fabric-item-api-v1 | Callbacks e extensões de itens: uso, atributos e comportamentos do item | **Port nativo.** Exige hooks para uso/consumo/durabilidade/tooltip e contexto de item; os campos declarativos existentes não substituem callbacks Java. |
| fabric-object-builder-api-v1 | Builders/fábricas de objetos de conteúdo e registros de tipos de objeto | **Port nativo.** Converter apenas subconjuntos representáveis no formato declarativo Horizon; serializadores e comportamentos customizados precisam de implementação própria. |
| fabric-recipe-api-v1 | Tipos, serializers e integrações de receitas | **Conteúdo, condicional + port nativo.** Receitas vanilla em JSON podem ser carregáveis quando o leitor do Horizon as implementa. Tipos/serializers Java próprios exigem adapter de recipes/registries. |
| fabric-loot-api-v3 | Modificar/registrar loot tables, condições e funções de loot | **Conteúdo, condicional + port nativo.** Loot JSON vanilla pode ser transferido, mas cada função/condição deve existir no runtime. Condições/funções Java não são convertíveis automaticamente. |
| fabric-tag-api-v1 | Registro e extensão de tags de blocos/itens/entidades e outros registries | **Conteúdo, condicional.** Ler tags JSON vanilla; tags customizadas precisam de tipo e consumidor no Horizon. |
| fabric-transfer-api-v1 | Modelo de transações para transferência de itens/fluidos entre inventários e storage | **Port nativo/alta complexidade.** Requer contratos de inventory/storage, operações atômicas e integração de itens/fluidos. |
| fabric-game-rule-api-v1 | Registrar e consultar regras de jogo customizadas | **Port nativo.** Precisa de registry de regras e serialização por mundo; não existe capability equivalente no SDK atual. |
| fabric-dimensions-v1 | Eventos/helpers e extensões relacionadas a dimensões e tipos de dimensão | **Port nativo/alta complexidade.** Primeiro expor dimensão e registries; criar dimensão nova afeta generator, save, rendering e protocolo. |

### Eventos e ciclo de vida

| Módulo Fabric | Responsabilidade principal | Estado Horizon e adaptação necessária |
|---|---|---|
| fabric-lifecycle-events-v1 | Ciclo de vida de cliente, mundo, servidor e ticks | **Parcial no SDK.** Tick/frame/init já existem para mods Horizon. Adicionar eventos nomeados de entrar/sair do mundo, carregar/descarregar nível, conexões e fases de tick com ordem definida. |
| fabric-entity-events-v1 | Eventos de criação, carregamento, remoção e mudanças de entidades | **Port nativo.** Criar event hooks com snapshot de entidade; eventos de servidor dependem de implementação server-side. |
| fabric-events-interaction-v0 | Eventos de pré-ataque, uso/interação com blocos, itens e entidades, quebra de bloco | **Port nativo.** Alta prioridade para mods de QoL; precisa de resultado/cancelamento bem definido e testes de sequência de input. |
| fabric-message-api-v1 | Hooks sobre mensagens/chat (recebimento, envio e apresentação, conforme caminho da versão) | **Parcial no SDK.** Existe leitura de chat e mensagem local; falta ciclo completo de mensagens, tipos/assinaturas e semântica de rede. |
| fabric-command-api-v2 | Registro e execução de comandos por meio da infraestrutura de comandos do jogo | **Parcial no SDK.** Comandos Horizon usam nome + texto livre; mapear subconjunto para argumentos tipados, autocomplete, permissões e contexto de cliente/servidor. |
| fabric-key-mapping-api-v1 | Registrar keybindings, categorias, estados e eventos de pressionar/soltar | **Adaptador novo.** Interface keys no WIT, integração com Controls, foco de tela, teclas físicas e controles touch sem colidir com input do jogo. |
| fabric-permission-api-v1 | Consultar permissões de comandos/ações, normalmente ligadas ao servidor | **Port nativo.** Definir fonte de verdade do lado servidor e não simular permissões do servidor remoto no cliente. |
| fabric-menu-api-v1 | Integração extensível com menus/telas do jogo e seus contextos | **Adaptador novo/port nativo.** API UI declarativa do Horizon, widgets e navegação; não expor screen objects Java. |
| fabric-screen-api-v1 | Hooks de telas: abertura, inicialização, renderização e input/fechamento | **Adaptador novo.** Criar capability ui e ciclo de vida de telas com árvore de widgets; orçamento e validação de input são essenciais. |
| fabric-creative-tab-api-v1 | Registrar categorias/abas criativas e decidir quais itens aparecem nelas | **Port nativo.** Adicionar uma API declarativa de grupos criativos após existir suporte a registries/itens; callback Java de preenchimento exige port. |

### Rede, sync e dados

| Módulo Fabric | Responsabilidade principal | Estado Horizon e adaptação necessária |
|---|---|---|
| fabric-networking-api-v1 | Canais/payloads customizados cliente-servidor e callbacks de conexão | **Port nativo/alta complexidade.** Projetar capability net limitada a canais explicitamente autorizados pelo mod, limites de tamanho, rate limits e protocolo compatível no outro lado. Não mudar handshake/brand para fingir compatibilidade Fabric. |
| fabric-registry-sync-v0 | Sincronização de registries customizados entre cliente e servidor | **Port nativo/alta complexidade.** Depende de registry extensível, protocolo de negociação e versão dos dados. Sem servidor Horizon/Fabric compatível, não ativa conteúdo Java customizado em servidores comuns. |
| fabric-data-attachment-api-v1 | Anexar dados serializáveis a entidades, block entities, níveis ou outros objetos | **Adaptador novo/port nativo.** Exige armazenamento tipado, lifecycle e serialização/save; escopo por entidade/mundo e migração precisam ser definidos. |
| fabric-serialization-api-v1 | Codificadores/decodificadores (Codec/MapCodec e integração de dados) | **Adaptador novo.** Definir um formato serializável estável no SDK; não reutilizar automaticamente classes/Codec Java. |
| fabric-resource-conditions-api-v1 | Condições que controlam se recursos/data são carregados, frequentemente com dependências de mod | **Conteúdo, condicional.** Avaliar condições conhecidas do JSON; condição referenciando registries, mods ou código inexistentes precisa ser sinalizada como não suportada. |
| fabric-resource-loader-v1 | Packs embutidos, namespaces, resource reload e listeners para assets/data | **Conteúdo, condicional + adapter.** O loader de conteúdo deve ordenar namespaces, resolver prioridade e recarregar de forma atômica; listeners Java exigem port nativo. |
| deprecated:fabric-resource-loader-v0 | APIs antigas de resource loader mantidas apenas para compatibilidade | **Sem implementação direta.** Mapear cada uso para recurso-loader-v1 quando possível; manter lista de compatibilidade por versão, sem criar shim genérico vazio. |

### Renderização, modelos, som e efeitos

| Módulo Fabric | Responsabilidade principal | Estado Horizon e adaptação necessária |
|---|---|---|
| fabric-rendering-v1 | Hooks de renderização de HUD, mundo, entidades, block entities, armadura, tooltip e fases de render | **Port nativo/alta complexidade.** A API HUD atual só desenha linhas de texto. Criar render API explícita por comandos (linhas, caixas, sprites, texto e item icons) com limites por frame. |
| fabric-renderer-api-v1 | Abstração de renderer/modelos e meshes para compatibilidade entre renderer mods | **Port nativo/alta complexidade.** Definir renderer independente, sem expor tipos Java; exige interface estável de modelo/mesh e compatibilidade com backend gráfico Horizon. |
| fabric-renderer-indigo | Implementação concreta Indigo da abstração Fabric Renderer | **Não portar como módulo de API.** Traduzir o resultado visual/formatos suportados; Indigo é uma implementação, não um conjunto de eventos portável. |
| fabric-model-loading-api-v1 | Registradores de modelos, model loaders, resolvers e callbacks de preparação de modelos | **Port nativo.** Criar loader nativo de modelos e declaração de dependências; arquivos JSON/OBJ suportados não substituem callbacks Java de model-loader. |
| fabric-rendering-fluids-v1 | Renderizadores, cores e hooks de renderização de fluidos | **Port nativo.** Depende de render API e do modelo de fluido no Horizon; comportamentos de simulação pertencem também a APIs de bloco/fluidos. |
| fabric-particles-v1 | Registrar tipos/fábricas e desenhar partículas customizadas | **Port nativo.** Definir formato declarativo para partículas comuns; fábricas Java e comportamento por tick requerem implementação de código nativo/Horizon. |
| fabric-sound-api-v1 | Eventos, fontes e helpers de sons customizados | **Conteúdo, condicional + port nativo.** Arquivos sounds.json/áudio podem ser transportados se o leitor suporta o formato; callbacks de som e registro precisam de API nativa. |

### Ferramentas, testes e diagnósticos

| Módulo Fabric | Responsabilidade principal | Estado Horizon e adaptação necessária |
|---|---|---|
| fabric-client-gametest-api-v1 | Harness de testes do lado cliente para mods | **Build/dev.** Criar suíte de testes de compatibilidade para APIs Horizon; não precisa fazer parte do runtime normal do jogador. |
| fabric-gametest-api-v1 | Framework de GameTest para lógica do jogo e mods | **Build/dev + server.** Exige ambiente determinístico de teste, mundo simulado e runners para código Horizon. |
| fabric-data-generation-api-v1 | Geradores de assets/data para ambiente de desenvolvimento | **Build/dev.** Não executar datagen Java no telefone; aceitar os JSON gerados e validar no import/CI. |
| fabric-debug-api-v1 | APIs/hook de diagnóstico e informação visual de debug | **Parcial/port nativo.** Começar por um canal de log/diagnóstico; qualquer overlay deve usar a render API segura do Horizon. |
| fabric-crash-report-info-v1 | Adicionar seções e contexto aos relatórios de crash | **Parcial.** Oferecer anexos de diagnóstico por mod (ID/versão/último evento) sem permitir travar ou expor dados privados; integrar com crash reporter Rust. |
| fabric-transitive-access-wideners-v1 | Propaga access wideners entre dependências/builds para tornar membros acessíveis | **Sem tradução binária direta.** É uma ferramenta/metadado do ecossistema Java. O Horizon não tem membros/classe Java a abrir; converter para capability explícita ou portar a operação necessária. |

## APIs fora da lista de módulos, mas fundamentais para mods Fabric

| Camada | O que inclui | Decisão para Horizon |
|---|---|---|
| Fabric Loader | fabric.mod.json, descoberta de mods, dependências, environment/client/common entrypoints, ModInitializer/ClientModInitializer, ordenação e resolução de dependências | Implementar parser/relatório de dependências para auditoria e import; NÃO chamar entrypoints Java. Dependências devem virar status/avisos até existir um port nativo equivalente. |
| Mixin / MixinExtras | @Inject, @Redirect, @ModifyArg, @ModifyVariable, @Overwrite, mixins de accessors/invokers e mixin configs | Não é adaptável com um mapa de nomes: é patch de bytecode em classes Minecraft Java que não existem no Horizon. Registrar como bloqueador de port e inspecionar os targets apenas para ajudar o autor do port. |
| Access wideners | Alterar visibilidade de classes/membros Java | Não têm alvo equivalente no Rust/WASM. Traduzir intenção para API explícita ou código reescrito no SDK Horizon. |
| Chamadas diretas a Minecraft/Yarn/Mojang/intermediary | Uso de objetos e métodos como MinecraftClient, World, Entity, DrawContext, ItemStack, etc. | Mesmo quando usa Fabric API, o mod pode depender diretamente das classes Java. É necessário mapear referências de bytecode e apontar as capacidades Horizon necessárias; não declarar compatível só por importar Fabric API. |
| Bibliotecas auxiliares (Architectury, Cloth Config, config libs, MixinExtras, etc.) | API de terceiros que o mod exige além do Fabric API | Enumerar como dependências independentes. Cada biblioteca pode adicionar suas próprias dependências e padrões incompatíveis; não faz parte da lista oficial de módulos Fabric API acima. |

## Matriz de prioridade sugerida para adaptar o SDK Horizon

| Prioridade | API/capability a adicionar | Por que primeiro | Contrato mínimo proposto |
|---|---|---|---|
| P0 — auditoria exata | Relatório por JAR com módulos Fabric encontrados, entrypoints, dependências, mixins, access wideners, referências Minecraft/Fabric e recursos | Permite decidir automaticamente conversão de conteúdo versus port de código, sem executar o JAR | JSON versionado: módulo detectado, símbolos encontrados, evidência, dependências ausentes, arquivos convertidos e avisos por recurso |
| P1 — mods simples/QoL | Events nomeados; keybindings; comandos tipados; chat lifecycle; HUD widgets/texto com posicionamento; config por mod | Expande as APIs existentes e cobre mods de utilidade pequenos sem mutações complexas | WIT versionado; snapshots imutáveis; registro no init; quotas de callback; capability separada por funcionalidade |
| P2 — leitura do mundo | inventory-read; target; world-read de blocos/biomas; recipes lookup; advancements; estado climático/dimensão | Base para HUDs de comida/durabilidade, tooltips, mapas, recipe viewers e overlays | Respostas snapshot/limitadas por distância; identificadores namespaced; nenhum ponteiro para objetos de jogo |
| P3 — interação e comunicação | Eventos canceláveis de interação/quebra/uso; ações seguras; UI/screens; networking com custom payload limitado | Permite mods interativos, mas aumenta risco de bugs e incompatibilidade de protocolo | Separar eventos pre/post; validar autoridade cliente/servidor; negar por padrão capacidades de escrita/rede |
| P4 — conteúdo extensível | Registry sync; blocos/itens/entidades/fluídos/receitas customizados; tags/loot/worldgen/advancements completos | É a área que mais mexe no save, mundo, renderizador e protocolo | Definição de conteúdo declarativa versionada + implementação de comportamento por SDK Horizon; não fingir que um JSON registra comportamento Java |
| P5 — APIs de renderer/engine | Model loading, meshes, render mundo/HUD, partículas, sons, telas complexas | Maior esforço técnico, backend gráfico e performance são específicos do Horizon | API de desenho por buffers/comandos; limites por frame e validação antes de carregar assets |
| Fora do adaptador genérico | Mixins, access wideners e chamadas diretas a Minecraft classes | Sem runtime JVM/classe alvo, uma conversão automática geral não é factível | Port nativo feito pelo autor ou reimplementação deliberada de feature no SDK Horizon |

## Pipeline de diagnóstico que deve acompanhar o conversor JAR

1. Ler e validar fabric.mod.json, sem inicializar classes.
2. Inventariar cada módulo API pela presença de referências em constante pool e imports; procurar também entrypoints, mixin configs, access wideners, dependências e classes bibliotecas.
3. Separar o resultado em quatro grupos: conteúdo reaproveitável; conteúdo que depende de IDs não registrados; APIs que podem mapear para uma capability Horizon já existente; APIs que exigem nova capability/port nativo.
4. Para cada grupo, registrar evidência por classe/símbolo/arquivo. Uma referência encontrada é evidência de uso, não prova de que o método foi executado; ausência de uma referência literal também não prova ausência de dependência dinâmica.
5. Gerar o .hmod só para conteúdo declarado e validado pelo leitor do Horizon; não copiar classes .class, não executar JAR, não marcar o mod inteiro como compatível se o código continuar sem port.
6. Emitir teste/manual-validation checklist por formato: lang, textures, models/blockstates, sounds, recipes, tags, advancements, loot, functions/predicates, worldgen, resource conditions e data attachments. Um recurso copiado não deve ser mostrado como suportado até passar pelo carregador e aparecer corretamente em mundo real.

## Mudanças específicas do Minecraft 26.3

No anúncio oficial de 26.3, o Fabric documentou remoções de APIs substituídas por funcionalidades vanilla: CompostingChanceRegistry e FuelRegistry; FabricPotionBrewingBuilder; StrippableBlockRegistry, TillableBlockRegistry e FlattenableBlockRegistry. Também promoveu a fluid behavior API e adicionou callbacks de fluxo de fluidos. Isso mostra por que a matriz precisa ser versionada por alvo de Minecraft, e não presumir que símbolos de 1.21.x existam em 26.3: https://www.fabricmc.net/2026/09/15/263.html.

## Definição de pronto para declarar uma área compatível

Uma linha só muda de **planejada** para **parcialmente compatível** quando houver: (a) contrato WIT documentado; (b) implementação e capability no runtime; (c) testes de unidade; (d) mod mínimo de exemplo que usa o recurso; (e) teste de integração em Android e desktop; (f) comportamento Vanilla inalterado com Fabric Bridge desligado; (g) relato explícito de APIs e cenários não suportados.

**Conclusão:** a primeira entrega útil não é um tradutor genérico de bytecode. É um inventário que detecta a superfície usada por cada JAR e encaminha o conteúdo suportável para o carry-over do Horizon, enquanto indica quais APIs do SDK faltam para portar o comportamento. Isso torna os próximos passos verificáveis e evita dar ao jogador a impressão de que um JAR Java já está funcionando só porque foi importado.
