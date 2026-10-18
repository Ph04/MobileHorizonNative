# MobileHorizonNative

Projeto Android-first para preparar e integrar recursos de renderização distante inspirados no ecossistema Horizon, com foco em compatibilidade, desempenho e testes reais em dispositivos Android.

## Direção do projeto

O aplicativo não deve depender de Termux para preparar os arquivos. A preparação, validação e importação devem acontecer dentro do próprio Android, com interface simples e progresso visível.

Em vez de acoplar toda a aplicação diretamente ao Horizon, vamos separar o núcleo Android de uma **camada de compatibilidade**. Essa camada detecta capacidades, valida formatos/versões, adapta diferenças entre backends e escolhe um caminho suportado sem presumir que todos os dispositivos conseguem executar os mesmos recursos.

## Estado inicial

Este repositório novo está separado de `Ph04/MobileHorizon` para evitar misturar mudanças experimentais. O repositório foi criado vazio; esta documentação estabelece a base do trabalho, não afirma que o APK ou o backend nativo já estejam compilados.

O artefato anterior `HorizonAndroidPrep 0.9.7` incluía fonte e uma biblioteca ARM64 pré-compilada (`libhorizon_android.so`). A biblioteca precisa ser verificada por hash e ABI antes de ser integrada; não a substitua por um arquivo fictício nem recompile Rust automaticamente.

## Componentes planejados

- **Android UI**: fluxo limpo, seleção de pasta/arquivo e estados de progresso.
- **Native Preparation Core**: preparação local de arquivos sem Termux.
- **Compatibility Layer**: capabilities, validação de versão/ABI, adaptadores e fallback explícito.
- **Backend Adapter**: integração isolada com os componentes Horizon disponíveis.
- **Import & Verification**: importação com progresso, relatório de erros e verificação do resultado.
- **CI**: validação de estrutura e empacotamento de APK quando o projeto Android estiver presente; preservar bibliotecas nativas pré-compiladas autorizadas.

## Princípios

1. Android-first, sem exigir shell externo para o fluxo normal.
2. Não anunciar suporte antes de detectar e testar a capacidade necessária.
3. Falhas devem ser recuperáveis e explicadas na interface.
4. Não sobrescrever arquivos do usuário sem confirmação.
5. Separar APIs públicas Android, integração Horizon e código específico de dispositivo.
6. Testar primeiro o caminho seguro de preparação/importação; ativar recursos nativos apenas após validação.

## Próximos passos

Consulte [`docs/architecture.md`](docs/architecture.md) e [`docs/roadmap.md`](docs/roadmap.md).