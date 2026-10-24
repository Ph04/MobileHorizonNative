# Diagnóstico do conversor Fabric e da Bridge

## Arquivos gerados

- O conversor `tools/fabric_jar_converter.py` grava `fabric-conversion-trace.jsonl` dentro de `--output-dir` por padrão. Para escolher outro destino, use `--trace-log /caminho/trace.jsonl`.
- Quando um componente da Fabric Bridge P1 é iniciado, o runtime cria `fabric-bridge-runtime-trace.jsonl` na pasta onde os arquivos de mod (`.hmod`) estão instalados. O arquivo é compartilhado por todos os componentes P1 para manter uma sequência cronológica.
- O log de runtime gira ao passar de 8 MiB: o arquivo anterior é preservado como `fabric-bridge-runtime-trace.jsonl.previous`. Os registros são linhas JSON independentes (JSONL).

## O que é registrado

O log de conversão inclui o hash SHA-256 e resumo da auditoria, cada entrada encontrada no JAR, arquivos `assets/` e `data/` selecionados, arquivos descartados (incluindo classes Java e descritores de Mixin/access widener), tamanho e CRC das entradas selecionadas, criação de cada pack e o resultado ou erro de conversão. Ele não armazena os conteúdos dos arquivos.

O log P1 registra pedidos/início/fim da carga, imports validados, chamada de inicialização e duração, resumo dos eventos/comandos/teclas/widgets/configurações registrados, chamadas às APIs de registro, invocações de comandos, callbacks, duração, orçamento e combustível Wasmtime restante, além de falhas e interrupções. Para limitar o custo, callbacks `client_tick` são registrados no primeiro tick e depois a cada 100 chamadas; falhas de callback são sempre registradas. Outros callbacks são registrados individualmente.

Para evitar expor mensagens privadas, o trace não grava o conteúdo do chat, argumentos completos de comandos nem valores de configuração. Registra nomes/IDs e metadados necessários para diagnosticar o fluxo.

## Limites importantes

Esses logs registram decisões do conversor e ações observáveis pela API da Bridge. Eles **não** conseguem mostrar cada operação interna de um componente WASM, nem executar/observar um mod Java que o conversor marcou como `partial_resources_only`: classes Java, entrypoints e Mixins continuam sem conversão e não são executados pelo conversor. A instrumentação também só existe quando a Bridge P1 é carregada; com o perfil Fabric desativado, o caminho Vanilla não deve inicializar a Bridge nem gerar o log de runtime.
