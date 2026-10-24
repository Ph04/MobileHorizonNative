# Fabric API 26.3: auditor de conversão e critérios de compatibilidade

## Fonte oficial e versão fixada

O catálogo foi gerado da branch `26.3` de [FabricMC/fabric-api](https://github.com/FabricMC/fabric-api/tree/26.3), usando `settings.gradle`, `gradle.properties` e a árvore Git de fontes públicas Java:

- Minecraft alvo: **26.3**
- Fabric API: **0.165.0**
- Commit fonte: [2065040b236a0d5ac011bd6a45625dc49579b222](https://github.com/FabricMC/fabric-api/commit/2065040b236a0d5ac011bd6a45625dc49579b222)
- Módulos catalogados: **46**, incluindo o loader de recursos v0 depreciado.
- Classes públicas fonte mapeadas: **401**. O catálogo mapeia classes/fonte, não métodos inteiros nem semântica de execução.

O arquivo `tools/fabric_api_26_3_catalog.json` é a fonte de verdade para a auditoria CLI; o espelho `app/src/main/assets/fabric-api-26.3-catalog.json` é usado pelo Gerenciador de Recursos Android. Ambos devem ser atualizados juntos quando a versão-alvo mudar.

## Como usar

No ambiente de desenvolvimento:

```bash
python3 tools/fabric_bridge_audit.py caminho/do-mod.jar --output relatorio.json --markdown relatorio.md
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
```

No Android, importe um JAR pela ação **Importar e converter mod Fabric (.jar)**. A tela mostra módulos Fabric API reconhecidos, a dependência agregada `fabric-api`, a versão máxima de bytecode observada e avisos do auditor. O relatório é salvo no diretório privado do app `filesDir/horizon/fabric-bridge-audits/<mod-id>-audit.json`.

A auditoria examina `fabric.mod.json`, constantes de arquivos `.class`, versões de bytecode, arquivos de recursos e JARs aninhados declarados por `fabric.mod.json`. O código Java nunca é executado. O scanner Android aplica limites de tamanho por classe/JAR e um limite total de dados descompactados para evitar que um arquivo importado monopolize memória e CPU.

## O que significa o resultado

- **Módulo detectado:** uma classe referenciada corresponde a uma classe fonte pública no catálogo 26.3. É evidência estática, não garantia de que a API é usada em runtime nem de que o bridge já reproduz o comportamento.
- **Referência não mapeada:** a classe não tem correspondência exata no catálogo público. Precisa ser revista, especialmente quando vier de uma API nova, classe interna ou biblioteca sombreada.
- **Dependência `fabric-api`:** indica que o mod depende do artefato agregador. Isso não identifica, por si só, os módulos usados; a auditoria apresenta essa dependência e os símbolos detectados separadamente.
- **Bytecode acima do major 69:** excede o alvo Java 25 do Minecraft 26.3; exige uma decisão/recompilação/porte e não deve ser considerado pronto.
- **Conversão parcial de recursos:** assets e data podem ser levados para `.hmod` quando o conversor Horizon consegue validá-los. Isso não converte entrypoints, Mixins, access wideners, registries dinâmicos ou a lógica Java.

## Limite para compatibilidade total

Não existe um tradutor genérico seguro que transforme arbitrariamente bytecode Java, Mixins e chamadas diretas às classes privadas do Minecraft em lógica equivalente de Rust/WASM. O fluxo implementado é uma conversão **orientada por evidências**:

1. Copiar e validar recursos/dados portáveis.
2. Detectar módulos Fabric API e dependências usados.
3. Produzir um relatório dos pontos que exigem APIs nativas.
4. Implementar/portar o comportamento de cada mod para o SDK Horizon.
5. Validar os casos reais no runtime, em single-player e em servidores compatíveis.

A compatibilidade só deve ser anunciada por mod/API depois de haver um teste executável e uma verificação de que o caminho Vanilla permanece intacto com o perfil Fabric Bridge desligado. A contagem de classes mapeadas não representa uma porcentagem de conversão.
