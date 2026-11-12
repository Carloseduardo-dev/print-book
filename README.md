<div align="center">

<img src="docs/screenshots/icon.png" width="112" alt="Ícone do print-book" />

# print-book

### Guarde o que importa. Leia onde estiver.

Um organizador pessoal para Android que transforma o que você vê na tela em coleções organizadas, pesquisáveis e disponíveis offline: páginas dos livros dos seus apps de leitura, artigos, receitas, tutoriais, documentações e o que mais valer a pena guardar.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Android](https://img.shields.io/badge/Android-11%2B%20·%20API%2030-3DDC84?logo=android&logoColor=white)](#começando)
[![Tests](https://img.shields.io/badge/testes%20unitários-65%20passando-4E7A4B)](#qualidade)
[![Offline](https://img.shields.io/badge/funciona-100%25%20offline-B0602C)](#privacidade-e-uso-responsável)
[![Site](https://img.shields.io/badge/site-print--book--site.vercel.app-1C1A16)](https://print-book-site.vercel.app)

<img src="docs/screenshots/banner.png" alt="Telas do print-book: início, biblioteca, leitor, busca no texto e captura com a aura" />

</div>

<details>
<summary><b>English summary</b></summary>

**print-book** is an Android app (Kotlin, Jetpack Compose, Material 3) for keeping what matters: pages from the books in your reading apps, articles, recipes, tutorials and documentation. Through a floating overlay built on MediaProjection, it captures what is on screen in any app that allows screenshots and turns it into organized, offline collections with a full reader: zoom, filters, bookmarks, on-device OCR full-text search (ML Kit + SQLite FTS4) and PDF export. Everything stays private on the device and can be deleted at any time.

Engineering highlights: single-consent capture sessions (Android 14+), frame-stability and duplicate detection with pure, unit-tested Kotlin, an accessibility-driven auto page turner isolated in a separate product flavor, a Samsung One UI `Choreographer` pitfall diagnosed and fixed on a real device, and a custom design system with an animated "capture aura" rendered only *after* each frame is taken. The app never bypasses `FLAG_SECURE`.
</details>

---

## Sumário

- [Visão geral](#visão-geral)
- [Destaques](#destaques)
- [Telas](#telas)
- [Funcionalidades](#funcionalidades)
- [Como funciona](#como-funciona)
- [Arquitetura](#arquitetura)
- [Decisões de engenharia](#decisões-de-engenharia)
- [Qualidade](#qualidade)
- [Stack](#stack)
- [Começando](#começando)
- [Roadmap](#roadmap)
- [Privacidade e uso responsável](#privacidade-e-uso-responsável)
- [Autor](#autor)

---

## Visão geral

Todo dia a gente encontra algo que quer guardar: a página de um livro que marcou, um artigo para ler com calma, a receita da família, aquela documentação consultada toda semana, um tutorial passo a passo. Na prática, isso fica espalhado em favoritos, abas abertas, prints perdidos na galeria e apps que dependem de internet para abrir.

O **print-book** reúne tudo num só lugar. Ele captura o que aparece na tela de qualquer aplicativo que permita captura (leitores, navegador, PDFs, materiais de estudo) e organiza em coleções com busca no texto e uma leitura confortável, **mesmo sem conexão**: numa viagem, num voo, no metrô ou numa região sem sinal.

**Para que serve**
- **Leitura:** páginas dos livros que você já tem nos seus apps de leitura, para continuar lendo offline.
- **Receitas:** um caderno de receitas sempre à mão, até na cozinha sem Wi-Fi.
- **Estudo e trabalho:** documentações, artigos e tutoriais reunidos por assunto.
- **Organização pessoal:** guias, referências e anotações que você consulta sempre.
- **Catálogos:** coleções de imagens e inspirações, do seu jeito.

**Princípios do projeto**
- **Guardar sem esforço:** do toque na bolha à página organizada, sem sair do app em que você está.
- **Continuidade da leitura:** não perder o hábito nem o tempo de leitura por falta de rede.
- **Respeito ao conteúdo:** só o que já está visível na tela, de conteúdo ao qual o usuário tem acesso. Nenhuma proteção é contornada.
- **Privacidade por padrão:** tudo fica no aparelho, sem conta, sem servidor e sem backup em nuvem.
- **Leitura confortável:** a interface se recolhe para dar espaço ao texto.

---

## Destaques

| | |
|---|---|
| **Captura automática + Turbo** | Vira as páginas sozinho e para no fim do livro. No modo Turbo, **100 páginas em cerca de 2 minutos** (~1,2 s por página), com progresso e tempo restante. |
| **Busca no texto, offline** | OCR no próprio aparelho + índice FTS4 que ignora acentos ("aproximacao" encontra "aproximação"). |
| **Revisão na hora** | A bolha mostra a última captura; um toque abre a faixa de miniaturas para conferir e excluir uma página ruim sem sair do app de leitura. |
| **Precisão medida** | **200/200 páginas** capturadas sem perda ou duplicata em teste real; **482/482** páginas indexadas sem falha. |
| **Arquitetura limpa** | Single-Activity, MVVM, Hilt, Room, WorkManager; lógica de imagem em **funções puras testadas na JVM**. |
| **Ético por construção** | Respeita `FLAG_SECURE`; automação isolada numa variante de build que nem entra no APK publicável. |
| **Design system próprio** | Tokens claro/escuro, tipografia Geist, iconografia e ícone adaptativo próprios, e a animação "aura de captura". |

---

## Telas

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/01-inicio.png" width="220" /><br/><sub><b>Início</b>: continue de onde parou</sub></td>
    <td align="center"><img src="docs/screenshots/02-biblioteca.png" width="220" /><br/><sub><b>Biblioteca</b>: receitas, artigos, docs e livros</sub></td>
    <td align="center"><img src="docs/screenshots/05-leitor.png" width="220" /><br/><sub><b>Leitor</b>: um artigo guardado para ler offline</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/03-busca.png" width="220" /><br/><sub><b>Busca no texto</b>: "cenoura" dentro da receita</sub></td>
    <td align="center"><img src="docs/screenshots/04-detalhe.png" width="220" /><br/><sub><b>Detalhe</b>: progresso, capa e liberar espaço</sub></td>
    <td align="center"><img src="docs/screenshots/07-captura-aura.png" width="220" /><br/><sub><b>Captura</b>: a bolha guardando uma receita do navegador</sub></td>
  </tr>
</table>

<sub>Receitas, artigo, documentação e livros exibidos são conteúdo de demonstração original, criado para estas imagens.</sub>

---

## Funcionalidades

**Captura**
- Botão flutuante sobre o aplicativo em uso (overlay em Compose), que se esconde no instante da captura.
- Funciona sobre **qualquer aplicativo que permita captura de tela**: leitores, navegador, PDFs e materiais de estudo.
- Três modos:
  - **Manual:** um toque por página.
  - **Semiautomático:** o leitor vira a página e o app captura quando a tela estabiliza.
  - **Automático:** o app vira as páginas e reconhece o fim do livro.
  - **Turbo** (Semi e Automático): ~1,2 s por página, gravando em paralelo com a próxima virada.
- **Capturar o livro todo:** lê o total de páginas no rodapé do leitor (OCR offline), mostra o progresso e o tempo restante e para na última página.
- Só captura a página quando ela está **parada e nítida** (sem o fim da animação de virada nem a versão provisória borrada que alguns leitores desenham primeiro).
- Recorte automático das barras do leitor, dos rodapés de progresso e das margens, por perfil de aplicativo.
- Descarte de páginas repetidas e aviso imediato se o aplicativo de origem bloquear capturas.
- Três qualidades: **Máxima** (WebP sem perda), **Econômica** (−46%) e **Texto** (tons de cinza sem perda, −70%).
- **Liberar espaço:** compacta um livro já salvo em segundo plano (100 páginas: 19,3 MB → 7,5 MB em 9 s).

**Leitura**
- Zoom por pinça, duplo toque e botões; virada de página por toque nas bordas e pelas teclas de volume.
- Filtros de cor: original, noturno, sépia e escurecido.
- Marcadores, barra de progresso e retomada automática de onde parou.

**Organização**
- Biblioteca em grade ou lista, favoritos, coleções, ordenação e busca por título **e** por conteúdo.
- Editor de páginas: reordenar, girar, definir capa e excluir.
- Exportação para PDF (edição completa).

---

## Como funciona

```mermaid
sequenceDiagram
    autonumber
    participant U as Leitor
    participant B as Bolha (overlay)
    participant S as CaptureService
    participant P as MediaProjection
    participant A as Análise de quadro
    participant D as Room + arquivos
    participant W as WorkManager (OCR)

    U->>S: Inicia a captura (consentimento único da sessão)
    S->>P: Cria VirtualDisplay + ImageReader
    loop Cada página
        S->>B: Esconde a bolha
        P-->>S: Quadro limpo
        S->>A: Tela preta? Página estável? Repetida?
        A-->>S: Página válida + recorte
        S->>D: Salva WebP + metadados
        S->>B: Mostra a bolha e toca a aura
        opt Modo automático
            S->>U: Gesto de virar página (Acessibilidade)
        end
    end
    D->>W: Livro concluído → indexar texto
    W->>D: Texto + índice FTS4
```

---

## Arquitetura

```mermaid
flowchart LR
    subgraph UI["UI · Jetpack Compose"]
        Screens["Início · Biblioteca · Leitor<br/>Editor · Capturar · Ajustes"]
        Bubble["Bolha + Aura<br/>(overlays)"]
    end
    subgraph Capture["Captura · Foreground Service"]
        MP["MediaProjection"]
        Analysis["FrameSignature · AutoCaptureTrigger<br/>AutoTurnController · CropMath"]
        Turner["PageTurner<br/>(flavor completo)"]
    end
    subgraph Data["Dados"]
        Room[("Room<br/>livros · páginas · coleções<br/>texto + FTS4")]
        Files[("filesDir · WebP")]
        Store[("DataStore")]
    end
    Work["WorkManager<br/>OCR · ML Kit"]

    Bubble --> MP --> Analysis --> Files
    Analysis --> Room
    Turner -.-> MP
    Room --> Work --> Room
    Screens <--> Room
    Screens <--> Store
```

<details>
<summary><b>Estrutura do projeto</b></summary>

```
app/src/
├── main/java/com/printbook/app/
│   ├── capture/
│   │   ├── analysis/      # funções puras: estabilidade, duplicatas, tela preta, fim do livro
│   │   ├── overlay/       # bolha e aura (janelas TYPE_APPLICATION_OVERLAY em Compose)
│   │   ├── processing/    # quadro bruto → página recortada
│   │   ├── profile/       # perfis de recorte por aplicativo
│   │   ├── projection/    # sessão MediaProjection (VirtualDisplay + ImageReader)
│   │   └── service/       # foreground service e estado da captura
│   ├── data/              # Room (DAOs, entidades, FTS), arquivos, DataStore, repositórios, busca
│   ├── work/              # OCR em segundo plano (WorkManager + Hilt)
│   ├── export/            # contrato de exportação
│   ├── di/                # módulos Hilt
│   └── ui/                # telas Compose, componentes, navegação e tema
├── pessoal/               # edição completa: virada automática (Acessibilidade) e PDF
├── loja/                  # edição para lojas: sem automação e sem exportação
└── test/                  # testes unitários (JVM)
```
</details>

Documento completo de arquitetura: [`docs/SYSTEM_DESIGN.md`](docs/SYSTEM_DESIGN.md).

---

## Decisões de engenharia

| Desafio | Solução |
|---|---|
| O Android 14+ exige consentimento a **cada sessão** de captura | Uma sessão mantém **um único** `VirtualDisplay` vivo; um consentimento cobre o livro inteiro. |
| Capturar a tela **sem** a própria bolha aparecer | A bolha é escondida via alpha da janela, o app aguarda o compositor e só aceita quadros posteriores. Validado em 100% das páginas verificadas. |
| Na **One UI (Samsung)**, a captura travava sem erro | Diagnóstico em aparelho: o `Choreographer` é pausado com o app em segundo plano. A espera passou a usar tempo e chegada de quadros, com timeout. |
| Saber **quando** capturar sem o usuário tocar | `AutoCaptureTrigger`: máquina de estados sobre assinaturas de quadro (referência → mudança → estabilidade). |
| Detectar páginas repetidas e o **fim do livro** | Diferença média absoluta em miniaturas de luminância, mais robusta que hashes de 64 bits para páginas de texto. Também detecta a troca de tela do leitor (ex.: tela de loja no fim de uma amostra). |
| Overlay em tela cheia **sem bloquear toques** | Janela intocável com opacidade 0,8, o limite do Android para toques atravessarem sobreposições. |
| Automação sem comprometer a publicação | *Product flavors* + Hilt: o código de Acessibilidade existe só na edição completa e **não entra** no APK da edição para lojas. |
| Busca útil em texto de OCR | FTS4 com `unicode61 remove_diacritics`, consultas por prefixo sanitizadas e trechos destacados com `snippet()`. |

---

## Qualidade

**Testes unitários (JVM):** 65 testes cobrindo detecção de tela preta, gatilho de captura (incluindo o fim lento da animação), nitidez, leitura do indicador de páginas, ritmo e tempo restante, controlador do modo automático, recorte, formato WebP, utilitários de busca, navegação do leitor e escolha da capa.

```bash
./gradlew :app:testPessoalDebugUnitTest
```

**Medições em aparelho real** (Galaxy A56, Android 17), com metodologia e números em [`docs/medicoes-fase1.md`](docs/medicoes-fase1.md):

| Cenário | Resultado |
|---|---|
| Captura automática | **100/100 páginas em 5 min 47 s**, 0 duplicatas, parada automática |
| Captura automática **Turbo** | **100/100 páginas em 1 min 51 s** (~1,1 s/página), 0 perdidas, 0 repetidas, nenhuma no meio da animação |
| Captura semiautomática (2 livros) | **200/200 páginas**, nenhuma perdida ou repetida |
| Fim do livro | Detectado sem salvar a tela seguinte à última página |
| OCR offline | **482/482 páginas, 0 falhas**, de 0,4 a 1,7 s por página |
| Qualidade Econômica | **−46% de espaço** com texto nítido em resolução real |
| Qualidade Texto / compactar | **−70% de espaço** sem perda de nitidez (cinza sem perda); livro de 100 páginas compactado em 9 s |

---

## Stack

| Área | Tecnologias |
|---|---|
| Linguagem e UI | Kotlin 2.4 · Jetpack Compose · Material 3 · Navigation Compose (rotas tipadas) |
| Arquitetura | MVVM · Hilt · Coroutines e Flow |
| Persistência | Room (AutoMigration, FTS4) · DataStore · armazenamento privado em `filesDir` |
| Captura | MediaProjection · VirtualDisplay · ImageReader · Foreground Service · overlays · AccessibilityService |
| Imagem e texto | WebP · ML Kit Text Recognition (on-device) |
| Leitor | Telephoto (zoom com subsampling) · Coil 3 |
| Background | WorkManager com `@HiltWorker` |
| Testes | JUnit · Truth · Turbine · kotlinx-coroutines-test |

---

## Começando

**Requisitos:** Android Studio recente (AGP 9), JDK 17+ e um aparelho com Android 11 ou superior.

```bash
git clone https://github.com/Carloseduardo-dev/print-book.git
cd print-book

# Edição completa (inclui virada automática e exportação PDF)
./gradlew :app:installPessoalDebug

# Edição para lojas (sem automação e sem exportação)
./gradlew :app:installLojaDebug
```

| Variante | Público | Diferenças |
|---|---|---|
| `pessoal` | Distribuição direta (sideload) | Virada automática por Acessibilidade e exportação PDF |
| `loja` | Base para publicação em lojas | Sem automação e sem exportação, alinhada às políticas das lojas |

No Android Studio, escolha a variante em **View → Tool Windows → Build Variants**.

---

## Roadmap

- [x] Captura manual, semiautomática e automática
- [x] Biblioteca, leitor e editor de páginas
- [x] OCR offline, busca no texto e exportação PDF
- [x] Design system e identidade visual próprios
- [x] Modo Turbo, captura do livro todo e compactação de livros
- [ ] Captura de página longa (rolagem) para sites, artigos e receitas
- [ ] Perfil "Web/Documento" e modelos de coleção (Receitas, Estudos, Leitura)
- [ ] Turbo mais rápido também na captura de tela inteira (bolha escondida durante a sequência)
- [ ] Leitura em voz alta sobre o texto reconhecido
- [ ] Publicação da edição para lojas

**Visão:** um "modo viagem" para leitura offline tem espaço para virar parceria com as próprias plataformas de leitura, de forma oficial e licenciada, ampliando o acesso de quem lê.

---

## Privacidade e uso responsável

Política de privacidade completa: [print-book-site.vercel.app/privacidade](https://print-book-site.vercel.app/privacidade).

- O print-book captura **apenas o que já está visível na tela**, a partir de conteúdo ao qual o usuário tem acesso legítimo, para leitura pessoal offline.
- **Nunca contorna proteções:** se um aplicativo bloqueia capturas de tela (`FLAG_SECURE`), o print-book detecta a tela preta, avisa e interrompe.
- As páginas ficam **somente no aparelho**: fora da galeria, fora do backup em nuvem, sem conta e sem servidor.
- O usuário pode apagar qualquer livro a qualquer momento. A recomendação é remover as capturas quando o acesso à internet voltar.
- O serviço de Acessibilidade, presente só na edição completa, apenas executa gestos de virar página e identifica qual app está em primeiro plano. Ele **não lê** o conteúdo das telas.

O projeto não tem vínculo com nenhuma plataforma de leitura. Marcas citadas no código pertencem aos respectivos titulares.

---

## Autor

**Carlos Eduardo Ferreira**

[![GitHub](https://img.shields.io/badge/GitHub-Carloseduardo--dev-181717?logo=github)](https://github.com/Carloseduardo-dev)

Fontes Geist sob [SIL Open Font License](docs/licenses/Geist-OFL.txt). © Carlos Eduardo Ferreira. Todos os direitos reservados.
