# MobileHorizonNative — biblioteca e ferramentas locais

Esta etapa acrescenta ferramentas locais por instância ao launcher. Os arquivos preparados do Minecraft e o runtime Horizon permanecem compartilhados; mundos, opções, logs, capturas e pacotes locais continuam separados em cada diretório HORIZON_HOME.

## Perfis e contas

- Até 12 perfis offline, com seleção, criação e remoção. O nick offline é apenas uma identidade local; não autentica em servidores online nem concede licença.
- Várias contas Microsoft verificadas podem ser salvas e alternadas. Cada token de renovação é cifrado com uma chave própria não exportável do Android Keystore.
- Contas de instalações antigas são migradas do armazenamento único assim que a conta correspondente é acessada. Remover uma conta não remove os perfis offline nem as outras contas.
- O jogo renova a sessão e confirma novamente a licença Java antes do lançamento autenticado.

## Skins e capas

O gerenciador lê o perfil Minecraft da conta ativa e usa os endpoints autenticados de Minecraft Services para importar uma skin PNG de 64×64, escolher o modelo classic (Steve) ou slim (Alex), alternar skins já pertencentes à conta, redefinir a skin e ativar/ocultar capas que já pertençam a essa conta. A tela exibe a textura oficial 64×64 da skin ativa e identifica o modelo; um modelo 3D giratório ainda não está disponível.

## Servidores favoritos

Favoritos são armazenados por instância. A sincronização mescla os itens ao arquivo servers.dat do Minecraft sem apagar campos NBT desconhecidos nem a lista de servidores que já existia. A leitura limita o arquivo a 16 MiB comprimidos e 64 MiB descompactados. O cliente Android atual não expõe ainda uma chamada documentada para conectar automaticamente a um endereço; depois de sincronizar, escolha o servidor na tela Multiplayer do jogo.

## Catálogo de conteúdo

A pesquisa consulta a API pública do Modrinth para Minecraft 26.3. Resource packs vão para resourcepacks/ e shaders para shaderpacks/ da instância ativa; os downloads do CDN exigem hash SHA-512 correspondente ao catálogo.

Modpacks .mrpack só são aceitos quando o manifesto identifica Minecraft 26.3 e não declara carregadores adicionais. A instalação automática rejeita projetos com conteúdo em mods/ e extrai somente recursos e shaders de overrides/, client-overrides/ ou arquivos de conteúdo correspondentes no manifesto. Não há instalação de mods Fabric, Forge, Quilt ou NeoForge nesta linha vanilla do projeto. Os caminhos de arquivos ZIP são validados contra traversal e os tamanhos/quantidades são limitados.

## Capturas e console

- A galeria lista imagens em screenshots/ da instância ativa, mostra prévias locais, exporta por Android Storage Access Framework e permite excluir um arquivo após confirmação.
- O console pesquisa as linhas recentes de logs/latest.log e atualiza automaticamente enquanto a tela está aberta. A leitura fica limitada aos últimos 512 KiB e a visualização aos resultados recentes para evitar consumo excessivo de memória.
- Os arquivos permanecem no armazenamento privado do app até que o usuário exporte uma captura. Nenhum desses recursos envia dados de jogo para um serviço remoto.

## Limitações conhecidas

O gerenciador de skins dá suporte às duas variantes de modelo, mas ainda não inclui um renderizador 3D giratório. A tela de servidores mantém favoritos e atualiza servers.dat, mas uma ação de conexão direta depende de futura API no motor Horizon. Modpacks que exigem carregadores ou mods são bloqueados intencionalmente.