# Prompt para o Claude Design: identidade visual do print-book

> Cole o texto abaixo no Claude Design (tipo **Design** para as telas e tipo **Design System** para tokens, componentes e ícones). Se o Claude Design tiver acesso ao repositório local `PrintBook`, os arquivos de referência estão listados no fim.

---

## Contexto do produto

Você vai criar a identidade visual e o design das telas do **print-book**, um app Android (Kotlin + Jetpack Compose + Material 3) para **leitura offline**.

**O problema que ele resolve:** quem assina apps de leitura (Kindle, Play Livros, Minha Biblioteca) muitas vezes não consegue ler sem internet. O print-book captura as páginas que o leitor exibe na tela, com um **botão flutuante sobreposto** ao app de leitura, e organiza essas capturas em "print-books": livros feitos de imagens, que você lê em qualquer lugar, sem conexão.

**O que o app já faz** (o design precisa cobrir tudo isto):
- **Captura:** botão flutuante (bolha arrastável) sobre o app de leitura, com três modos:
  - Manual: toque para capturar.
  - Semiautomático: o usuário vira a página e o app captura quando a tela para de mudar.
  - Automático: o app vira as páginas sozinho e para no fim do livro.

  Recorta barras e margens, ignora páginas repetidas e mostra um contador ao vivo.
- **Biblioteca:**
  - grade ou lista de capas, com ordenação (recentes, título, adicionados);
  - Favoritos e Coleções;
  - busca por título **e pelo texto das páginas** (OCR offline), com o trecho encontrado destacado.
- **Leitor:**
  - páginas em imagem, deslizando para os lados;
  - zoom por pinça, duplo toque e botões +/−;
  - toque nas bordas e teclas de volume viram a página;
  - filtros de cor (original, invertido/noturno, sépia, escurecido);
  - marcadores, barra de progresso "Página X / N" e busca dentro do livro;
  - modo tela cheia.
- **Detalhe do livro:**
  - capa, título, autor, nº de páginas e tamanho;
  - progresso de leitura e "Indexando texto: 45/100";
  - continuar lendo, editar páginas, coleções e exportar PDF.
- **Editor de páginas:** grade de miniaturas com seleção múltipla para reordenar, girar, definir capa e excluir.
- **Início:** card "Continuar lendo" com a barra de progresso, prateleiras "Lidos recentemente" e "Adicionados recentemente", e uma chamada para capturar um novo livro.
- **Capturar:**
  - permissões e escolha do app de origem;
  - modo, qualidade (Máxima/Econômica) e recorte;
  - card de ativação da "virada automática", que usa Acessibilidade;
  - botão fixo "Iniciar captura";
  - estado "capturando" com estatísticas ao vivo.
- **Ajustes:** tema (sistema/claro/escuro), opções de leitura, opções de captura, armazenamento e "Sobre".
- **Navegação:** barra inferior com 4 abas (Início, Biblioteca, Capturar, Ajustes).

**Usuário principal:** o próprio criador do app. É **desenvolvedor**, lê muito (livros técnicos, de cálculo e de programação, em PT e EN) e gosta de **estética tech**. Ainda assim, o app precisa ser **calmo e confortável como um leitor de verdade** (pense na sobriedade do Kindle e do Play Livros, mas com personalidade própria).

---

## Direção criativa (o mais importante)

1. **Seja criativo e inovador. Não copie** Kindle, Play Livros, Apple Books, Kobo, Mihon nem nenhum outro app. Inspire-se no nível de sobriedade e conforto deles, nunca no visual. Nada de reproduzir layouts, ícones, cores de marca ou elementos reconhecíveis de outros produtos.
2. **Tech, mas sutil.** A personalidade de desenvolvedor aparece nos **detalhes**, sem disputar atenção com a leitura:
   - metadados em fonte monoespaçada (ex.: `p. 37/120 · 2,4 MB · ocr ✓`);
   - microtipografia precisa e grids visíveis em estados vazios;
   - indicadores de status em estilo "terminal";
   - a ideia de "print/captura" (moldura de recorte, cantos de crop, scanline) como motivo gráfico recorrente.
3. **A leitura vem primeiro.** No **Leitor**, a interface praticamente desaparece:
   - controles discretos, contraste confortável e nada de cores saturadas perto do texto;
   - nenhuma animação que canse;
   - fundo e cromo pensados para sessões longas, inclusive à noite;
   - o filtro "noturno" precisa ser realmente agradável.
4. **Claro e escuro de primeira classe.** Os dois temas devem ter o mesmo cuidado. No escuro, evite preto puro na interface (exceto atrás da página no leitor) e use um tom de "tinta".
5. **Acessibilidade:**
   - contraste WCAG AA no mínimo;
   - áreas de toque de 48dp;
   - tipografia legível em telas de 6–7";
   - suporte a fonte grande do sistema.
6. **Movimento:** transições curtas e funcionais. Na captura, uma micro-animação de "flash/crop" quando uma página é salva.

---

## Entregáveis

### A. Duas referências de design (duas direções completas e distintas)

Para **cada** direção, entregue:
- nome e conceito em 2–3 frases;
- paleta (claro e escuro) e tipografia, com fontes livres (Google Fonts / OFL);
- as telas principais: **Início, Biblioteca (grade), Detalhe do livro, Leitor (com cromo visível e em tela cheia), Capturar (configuração e "capturando") e a bolha flutuante sobre um app de leitura genérico**;
- um momento "assinatura" que torna essa direção memorável.

Sugestões de ponto de partida, que você pode reinterpretar ou substituir por algo melhor:
- **Direção 1, "Ink & Terminal":**
  - papel quente e tinta escura;
  - títulos em serifada moderna, metadados em monoespaçada;
  - acento único discreto (ex.: âmbar/cobre);
  - motivo de "cantos de recorte" nas capas e na bolha.
- **Direção 2, "Night Shift / Glass":**
  - base fria azul-grafite;
  - superfícies em camadas com leve translucidez;
  - sans geométrica + monoespaçada;
  - acento ciano/verde-fosfóro bem dessaturado;
  - estética de "IDE noturna", sem perder o conforto.

### B. Ícone do app (app icon)
- 3 conceitos e 1 escolhido, refinado.
- Formato **Android adaptive icon**: camadas foreground + background, área segura de 66dp em 108dp. Inclua a versão **monocromática (themed icon do Android 13+)**.
- Deve funcionar em 48dp e em tamanho grande, e evitar texto pequeno.
- Ideia-base, que pode ser reinterpretada: a união de **livro + captura**. Por exemplo, uma página/livro aberto dentro de uma moldura de recorte, ou um marcador que vira um "obturador".
- Original: não pode lembrar o ícone de nenhum app existente.

### C. Iconografia própria
Conjunto coerente (mesma grade, traço e cantos), em SVG / Android Vector Drawable 24dp:
- abas: Início, Biblioteca, Capturar, Ajustes;
- captura: capturar, automático (play/pause), concluir, página repetida, tela bloqueada/preta, recortar;
- leitor: zoom +/−, ajustar à tela, filtro de cor, marcador, buscar no livro, girar;
- editor/biblioteca: favorito, coleção, exportar PDF, OCR/texto indexado, armazenamento.

### D. Design System (para virar código Compose)
- **Tokens:** cores (claro/escuro, papéis Material 3: primary, surface, surfaceVariant, outline…), tipografia (escala completa), espaçamentos, raios e elevação.
- **Componentes:**
  - capa de livro (com e sem imagem), item de grade e de lista, card "Continuar lendo";
  - chips de filtro, barra de progresso de leitura;
  - cromo superior/inferior do leitor, bolha flutuante (estados idle/capturando/automático/aviso);
  - card de permissão, resultado de busca com trecho destacado;
  - estados vazios.
- Entregue os tokens num formato fácil de portar para `Color.kt`, `Type.kt` e `Theme.kt` do Jetpack Compose (nomes de variáveis e valores hex/sp/dp).

---

## Restrições técnicas

- Android 11+ (minSdk 30), tela de referência **1080×2340 (Galaxy A56)**, edge-to-edge, barra inferior Material 3.
- As páginas dos livros são **imagens** (capturas de tela). O leitor mostra imagens com zoom, não texto refluível: projete o cromo ao redor da imagem.
- A bolha flutuante aparece **por cima de outros apps**. Deve ser pequena (~70×150dp), arrastável, legível sobre fundo claro ou escuro, e some no instante da captura.
- Sem dependência de fontes pagas. Os ícones devem poder virar Vector Drawables.
- Idioma da interface: **português do Brasil**.

## Arquivos de referência no repositório (se houver acesso ao repo local)

- `docs/SYSTEM_DESIGN.md`: arquitetura e telas.
- `reports/Viabilidade app print book.md`: pesquisa de funcionalidades de apps de leitura.
- `app/src/main/java/com/printbook/app/ui/`: telas atuais em Compose (`home/`, `library/`, `bookdetail/`, `reader/`, `editor/`, `capture/`, `settings/`, `components/`).
- `app/src/main/java/com/printbook/app/capture/overlay/BubbleContent.kt`: bolha flutuante atual.
- `app/src/main/java/com/printbook/app/ui/theme/`: tema atual (template padrão, que será substituído).
