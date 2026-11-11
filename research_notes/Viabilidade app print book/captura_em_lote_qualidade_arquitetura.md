# Captura em lote, qualidade/armazenamento das páginas e arquitetura recomendada (print-book, Kotlin + Compose)

> Nota de método: pesquisa feita em 10/11/2026. Os créditos do Firecrawl acabaram no meio da pesquisa e o restante foi feito com WebSearch/WebFetch. As afirmações com fonte estão em "Cited Findings". Recomendações de projeto e estimativas numéricas sem benchmark direto ficam em "Inferences" e estão marcadas como tal.

## 1. Loop automático de virar página + captura (AccessibilityService / MediaProjection)

### Takeaway
Dá para montar um loop sem root com duas peças. Uma AccessibilityService vira a página com `dispatchGesture` (toque na borda direita ou swipe). A captura vem de uma MediaProjection: uma sessão consentida (VirtualDisplay + ImageReader) que fica aberta durante o lote todo. A alternativa é `AccessibilityService.takeScreenshot`, que tem limite de frequência (o AOSP indica cerca de 1 s entre chamadas). O modo semiautomático (o usuário vira, o app detecta a mudança de frame e captura) usa a mesma sessão de MediaProjection.

### Cited Findings
- Gestos sintéticos: `GestureDescription.StrokeDescription(path, startTime, duration)` + `GestureDescription.Builder().addStroke()` + `dispatchGesture(gesture, GestureResultCallback, handler)`. O exemplo oficial usa um swipe de 500 ms com `onCompleted` no callback. — [Android Developers: Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)
- `dispatchGesture` retorna um boolean que diz se o gesto foi aceito para despacho. `GestureResultCallback` informa o status (completed/cancelled). — [AccessibilityService reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- `AccessibilityService.takeScreenshot(displayId, executor, callback)` (API 30+) tem os códigos de erro `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (pouco tempo desde a última captura), `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (janela com conteúdo seguro, ou seja, FLAG_SECURE) e `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS`, entre outros. — [AccessibilityService reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- Um commit do framework define `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 1000`, o que sugere no mínimo cerca de 1 s entre capturas via takeScreenshot. Não confirmei o valor no AOSP atual. — [AOSP commit "Improvement for takeScreenshot() API"](https://android.googlesource.com/platform/frameworks/base/+/5519738da5de%5E%21/); [commit de implementação](https://android.googlesource.com/platform/frameworks/base/+/09789e41686d%5E%21)
- MediaProjection: o conteúdo é projetado num VirtualDisplay que renderiza num `Surface` fornecido pelo app (por exemplo `ImageReader`, que permite processar os frames em tempo real). — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- Consentimento: é preciso pedir ao usuário antes de cada sessão. Uma sessão é uma chamada a `createVirtualDisplay()`. No Android 14+, reusar o Intent de `createScreenCaptureIntent()` ou chamar `createVirtualDisplay()` duas vezes na mesma MediaProjection lança `SecurityException`. — [Media projection](https://developer.android.com/media/grow/media-projection)
- Com targetSdk 34+, o manifesto precisa de `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` e de um serviço com `foregroundServiceType="mediaProjection"`. — [Media projection](https://developer.android.com/media/grow/media-projection)
- O callback `MediaProjection.Callback.onStop()` é obrigatório. Sem ele, `createVirtualDisplay()` lança `IllegalStateException`. A sessão termina quando o usuário toca no chip da status bar, quando a tela é bloqueada, quando outra projeção começa ou quando o processo morre. — [Media projection](https://developer.android.com/media/grow/media-projection)
- Android 14 introduziu o "app screen sharing": o usuário pode compartilhar só uma janela de app, e a status bar, a barra de navegação e as notificações ficam de fora. `onCapturedContentResize()` permite redimensionar o VirtualDisplay para o conteúdo capturado. — [Media projection](https://developer.android.com/media/grow/media-projection)
- A partir do Android 15 QPR1 aparece um chip grande na status bar durante a projeção, e a projeção para quando a tela é bloqueada. — [Media projection](https://developer.android.com/media/grow/media-projection)

### Inferences
- **Fluxo recomendado (inferência de projeto):** o usuário abre a sessão de captura no app. Uma única MediaProjection + VirtualDisplay + ImageReader (RGBA_8888, resolução nativa) roda num foreground service. A cada iteração: (1) `dispatchGesture` com tap curto (cerca de 50 ms) a ~90% da largura e ~50% da altura; (2) espera inicial configurável; (3) "settle detection", que lê frames do ImageReader até dois frames consecutivos ficarem iguais (diferença de pixels em grade reduzida abaixo de um limiar) por cerca de 150-250 ms; (4) grava o frame; (5) checa duplicata e fim do livro (seção 2).
- Captura via MediaProjection evita o limite de ~1 s do `takeScreenshot`. O `takeScreenshot` serve como modo alternativo simples (sem consentimento por sessão, mas dá no máximo ~1 página/s, o que é aceitável para 100 páginas, cerca de 2-3 min).
- Qualquer app que use FLAG_SECURE aparece preto ou falha (`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`). Isso precisa ser detectado (frame quase todo preto) e explicado ao usuário. Se Kindle e outros leitores usam FLAG_SECURE depende do app e da versão, e não verifiquei aqui.
- Um tap em vez de swipe reduz o tempo de animação em leitores que viram página no toque. Delay inicial sugerido de 300-600 ms, mais settle detection. Como a detecção adapta o tempo, não é preciso cravar um delay fixo.
- O chip/auto-stop do Android 15+ significa que bloquear a tela no meio do lote encerra a sessão. É preciso persistir o progresso e permitir retomar com novo consentimento.

### Gaps
- Não achei fonte confiável com o tempo de animação de virar página do Kindle Android (nem se a animação pode ser desligada nas configurações atuais). Precisa medir no aparelho.
- O intervalo exato do `takeScreenshot` no AOSP atual (1000 ms vs outro valor) não foi confirmado na referência pública.
- As políticas do Google Play para uso de AccessibilityService fora de acessibilidade (declaração obrigatória e risco de rejeição) não foram pesquisadas aqui. Provavelmente estão cobertas por outro pesquisador, mas são críticas para a viabilidade.

## 2. Detecção de página duplicada / fim do livro (perceptual hashing)

### Takeaway
Hashes perceptuais de 64 bits (aHash/dHash/pHash) comparados por distância de Hamming são o padrão para detectar quase-duplicatas. Distâncias de até ~5 bits costumam indicar a mesma imagem. Para páginas de texto capturadas do mesmo app (sem recompressão), as distâncias entre páginas iguais tendem a ser quase 0. A regra de fim do livro pode ser "N capturas seguidas (ex.: 3) com distância ≤ limiar após o virar de página".

### Cited Findings
- pHash (método DCT): reduz a imagem para 32×32 em tons de cinza, aplica DCT 2D, pega o bloco 8×8 de baixa frequência, compara com a mediana (ignorando o termo DC) e gera 64 bits. A comparação é por distância de Hamming (contagem de bits diferentes). — [Mixpeek: Perceptual image hashing guide](https://mixpeek.com/guides/perceptual-image-hashing-near-duplicate-detection)
- Com pHash, uma distância de Hamming ≤ ~5 tende a indicar quase-duplicatas. Imagens muito parecidas dão distâncias de 2-3. — [context.dev: Perceptual hashing with sharp-phash](https://www.context.dev/blog/perceptual-hashing-in-node-js-with-sharp-phash-for-developers)
- dHash (difference hash) existe como alternativa simples em Java, portável para Kotlin. — [MojoAuth: dHash in Java](https://mojoauth.com/security-guides/dhash-in-java)
- Há uma avaliação acadêmica comparando hashing perceptual com embeddings profundos para deduplicação. Menor distância de Hamming significa maior semelhança. — [MDPI Electronics 15(7):1493](https://www.mdpi.com/2079-9292/15/7/1493)
- O Immich discute pHash + limiar de Hamming para duplicatas entre dispositivos (exemplo de uso em produção). — [Immich discussion #22363](https://github.com/immich-app/immich/discussions/22363)

### Inferences
- **Cuidado específico para texto:** páginas de texto diferentes do mesmo livro são visualmente parecidas em baixa frequência (blocos de linhas cinza). aHash/pHash 8×8 pode dar distâncias pequenas entre páginas diferentes. Recomendações (inferência, a validar empiricamente):
  1. Calcular o hash só sobre a região recortada (sem status bar e sem rodapé com número de página/percentual, que mudam a cada página e "mascarariam" uma duplicata real, ou o contrário).
  2. Usar dHash com mais resolução (ex.: 16×16 → 256 bits) ou combinar com uma comparação direta de pixels num thumbnail (ex.: 64×142 em cinza, diferença média absoluta). Captura de tela sem recompressão é determinística, então páginas idênticas dão diferença ~0 e páginas diferentes dão diferença clara.
  3. Fim do livro: parar quando K=2-3 capturas consecutivas forem idênticas à anterior. Se o rodapé mostrar "100%"/"Fim", o OCR pode reforçar (opcional).
- Implementação em Kotlin puro é trivial (`Bitmap.createScaledBitmap` + `getPixels` + `Long.countOneBits()` para o Hamming). Não precisa de biblioteca externa.

### Gaps
- Não encontrei uma biblioteca Kotlin/Android de pHash mantida e conhecida, nem benchmarks de limiar específicos para páginas de texto. O limiar precisa ser calibrado com capturas reais.

## 3. Auto-crop de barras do sistema / chrome do leitor e melhoria de imagem

### Takeaway
No Android 14+, o "app screen sharing" da MediaProjection já exclui a status bar e a barra de navegação se o usuário escolher capturar só o app leitor. Para o resto (cabeçalho/rodapé do leitor), a abordagem mais robusta é o usuário definir uma vez um retângulo de recorte por livro/app, salvo e aplicado a todas as páginas.

### Cited Findings
- O app screen sharing (Android 14+) exclui status bar, barra de navegação, notificações e outros elementos de UI do sistema, mesmo com o app em tela cheia. — [Media projection](https://developer.android.com/media/grow/media-projection)
- O tamanho da projeção pode mudar (rotação, escolha de janela). `onCapturedContentResize()` serve para ajustar o VirtualDisplay e evitar letterbox. — [Media projection](https://developer.android.com/media/grow/media-projection)
- `WindowMetrics`/`getMaximumWindowMetrics()` é a forma recomendada de obter o tamanho da tela para a projeção. — [Media projection](https://developer.android.com/media/grow/media-projection)

### Inferences
- **UX de recorte (inferência):** na primeira captura, mostrar um editor de retângulo arrastável sobre a imagem. Guardar o crop em coordenadas normalizadas (0-1) no `Book` (Room), para sobreviver a mudanças de resolução. Aplicar com `Bitmap.createBitmap(src, x, y, w, h)` antes de comprimir.
- Auto-detecção opcional: procurar linhas horizontais de cor uniforme no topo/rodapé (o fundo da página do leitor costuma ser branco, sépia ou preto) para sugerir o crop inicial.
- Melhoria de imagem: guardar o original (lossless) e aplicar realce só na exibição (ColorMatrix de contraste/escala de cinza no Compose via `ColorFilter`). Binarização destrutiva prejudica imagens e ilustrações do livro. Se for usada, deve ser opcional e só para exportação.
- Converter para escala de cinza antes de gravar reduz o tamanho com WebP lossless em páginas só de texto (menos entropia de cor). Isso é inferência, não medido.

### Gaps
- Não pesquisei bibliotecas de processamento (OpenCV Android etc.). Para crop fixo + ColorMatrix, o SDK nativo basta.

## 4. Formato de imagem: PNG vs WebP lossless vs WebP lossy vs AVIF/JPEG

### Takeaway
Para screenshots de texto, WebP lossless (`Bitmap.CompressFormat.WEBP_LOSSLESS`, API 30+) é o padrão recomendado: sem perda de nitidez e cerca de 25% menor que PNG. WebP lossy com qualidade alta é bem menor, mas pode criar artefatos em volta das letras. JPEG deve ser evitado em texto.

### Cited Findings
- `Bitmap.CompressFormat` tem JPEG, PNG, WEBP (deprecado na API 30), `WEBP_LOSSLESS` (API 30) e `WEBP_LOSSY` (API 30). No PNG o parâmetro `quality` é ignorado. No `WEBP_LOSSLESS`, `quality` controla o esforço de compressão (0 = rápido e maior, 100 = lento e menor). Antes da API 30, `WEBP` com quality 100 gera lossless a partir do Android 10 (Q). — [Bitmap.CompressFormat reference](https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat)
- WebP lossless é ~26% menor que PNG. WebP lossy é 25-34% menor que JPEG em qualidade equivalente. — [Google: WebP](https://developers.google.com/speed/webp)
- Num estudo independente, a redução mediana do WebP lossless sobre PNG foi de 24,3% (percentil 85: 16,6%). — [ctrl.blog: lossless WebP vs FLIF vs PNG](https://www.ctrl.blog/entry/webp-flif-comparison/)
- Teste de terceiros: screenshots com média de 320 KB em JPG e 148 KB em WebP (54% menor). — [dev.to: PNG to JPG vs WebP 2026](https://dev.to/samma1997/png-to-jpg-vs-webp-which-one-should-you-actually-use-in-2026-4o2m)
- Para screenshots com texto pequeno, o critério recomendado é comparar PNG vs WebP lossless em tamanho e nitidez exata de pixel. — [Zipic: WebP vs PNG](https://zipic.app/blog/jpeg-vs-png-vs-webp/)
- O Telephoto tem suporte parcial a AVIF (desde 0.15.0). — [Telephoto releases](https://github.com/saket/telephoto/releases)

### Inferences
- **Estimativa de armazenamento (inferência, não medida):** uma página de texto 1080×2400 com fundo uniforme comprime muito bem. Ordem de grandeza esperada: PNG ~150-400 KB, WebP lossless ~110-300 KB (aplicando os ~25% de redução), WebP lossy q80-90 ~60-150 KB. Para 100 páginas: ~11-30 MB em WebP lossless, ~15-40 MB em PNG. Os valores precisam ser medidos com páginas reais (claro/escuro/sépia, com e sem ilustrações).
- Recomendação: WEBP_LOSSLESS com quality ~75-90 (bom equilíbrio esforço/tamanho), com minSdk 30. Abaixo da API 30, usar `WEBP` com quality 100 (lossless a partir do Android 10). Oferecer nas configurações a opção "economia: WebP lossy q85".
- O encode lossless de alto esforço pode levar centenas de ms por página. Durante a captura, salvar rápido (PNG ou WebP lossless com esforço baixo) e reencodar no WorkManager depois.
- AVIF não tem encoder no `Bitmap.compress` (só JPEG/PNG/WEBP*), então exigiria biblioteca nativa. Não compensa para este caso.

### Gaps
- Não achei benchmark publicado especificamente para uma página de livro 1080×2400 em PNG/WebP/AVIF. Os números acima são extrapolados.

## 5. OCR no dispositivo com ML Kit Text Recognition v2

### Takeaway
O ML Kit Text Recognition v2 (script latino) suporta português (`pt`, com `pt-PT` mapeado), funciona offline e tem duas formas de distribuição. A versão bundled (`com.google.mlkit:text-recognition:16.0.1`) acrescenta ~4 MB por arquitetura e funciona sem o Play Services. A versão unbundled (`play-services-mlkit-text-recognition:19.0.2`) acrescenta ~260 KB e baixa o modelo via Play Services. Não há números oficiais de latência. É preciso medir, mas a expectativa é algo da ordem de centenas de ms por página.

### Cited Findings
- Dependências: bundled `com.google.mlkit:text-recognition:16.0.1` (Latin) e unbundled `com.google.android.gms:play-services-mlkit-text-recognition:19.0.2`. Página atualizada em 09/11/2026. — [ML Kit Text Recognition v2 Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Impacto no tamanho: unbundled ~260 KB por arquitetura de script; bundled ~4 MB por script por arquitetura. — [ML Kit Text Recognition v2 Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Cada caractere deve ter pelo menos 16×16 px. Acima de 24×24 px geralmente não há ganho de precisão. Um documento em papel carta pode exigir ~720×1280. Reduzir a resolução diminui a latência. — [ML Kit Text Recognition v2 Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- A v2 reconhece texto em conjuntos de caracteres chinês, devanágari, japonês, coreano e latino. — [Text recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2)
- Português (`pt`) é suportado no grupo latino. `pt-PT` é mapeado para `pt`. — [ML Kit v2 languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- Latência: não há número oficial. Um relato antigo (2020, Firebase ML Kit, 640×480) fala em 400-800 ms no Android. Uma fonte terciária cita ~0,05 s no iPhone 12 sem metodologia. Os dois dados são fracos. — [resultados de busca agregados; ver Stack Overflow via kiwix](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/61538013/run-multiple-image-processor-using-firebase-mlkit)

### Inferences
- Para um app de leitura offline sem depender do Play Services, a versão bundled é a mais previsível: +~4 MB por ABI, mitigado pelo Android App Bundle, que entrega só a ABI do aparelho.
- Uma página de leitor em 1080×2400 com fonte normal tem caracteres bem acima de 16 px. Dá para reduzir para ~50-70% antes do OCR para ganhar velocidade sem perder precisão (inferência a validar).
- O OCR deve rodar em `CoroutineWorker` (WorkManager) depois da captura, com restrição de bateria não baixa. 100 páginas a ~0,3-1 s cada dá ~0,5-2 min (estimativa).
- Guardar o `Text` resultante (texto plano + blocos/linhas com bounding boxes em JSON) permite busca full-text (Room FTS4), destacar o termo buscado sobre a imagem e TTS via `android.speech.tts.TextToSpeech` com locale pt-BR.

### Gaps
- Não há benchmark oficial nem confiável de latência por página para a v2 no Android. Precisa medir no aparelho-alvo.
- A qualidade do OCR em itálico, notas de rodapé e hifenização em português não foi avaliada.

## 6. Arquitetura recomendada e bibliotecas (versões atuais, 2026)

### Takeaway
MVVM com camadas (ui → domain/use cases → data), Room para os metadados, imagens em `filesDir` interno, WorkManager para pós-processamento, Coil 3 + Telephoto para visualização com zoom e subsampling, DataStore para configurações, Hilt para DI e Navigation Compose (2.x) ou Navigation 3. Exportação para PDF via `android.graphics.pdf.PdfDocument` e para CBZ via `ZipOutputStream`.

### Cited Findings
- **Telephoto** (saket): a versão mais recente é a **0.19.0 (02/04/2026)**, que adiciona `interactionSource` e fallback para imagem não subamostrada quando falta a entrada no cache de disco. A 0.14.0 trouxe suporte a Coil 3 (`me.saket.telephoto:zoomable-image-coil3`) e restauração de zoom. A 0.17.0 trouxe `ZoomFocalPoint`, desligar zoom/pan seletivamente e tiles consistentes (útil para PDF). A 0.15.0 tornou `SubSamplingImageSource` não selado. — [Telephoto releases](https://github.com/saket/telephoto/releases); [mvnrepository](https://mvnrepository.com/artifact/me.saket.telephoto)
- **Coil 3**: a última versão encontrada é a **3.5.0** (`io.coil-kt.coil3:coil-compose:3.5.0`, ~10/06/2026), segundo agregadores. Não confirmei no changelog oficial. — [klibs.io coil](https://klibs.io/project/coil-kt/coil); [gitpedia coil-kt/coil](https://gitpedia.pages.dev/wiki/coil-kt/coil)
- **Jetpack (snapshots da página de versões, datas inconsistentes):** Room 2.8.5 (set/2026, e existe uma linha separada Room3 3.0.x); Navigation 2.10.1; navigation3 1.1.7 estável (1.2.0-rc01 em RC); WorkManager 2.11.2 estável (2.12.0-alpha01 em pré-lançamento). — [AndroidX stable channel](https://developer.android.com/jetpack/androidx/versions/stable-channel); [AndroidX releases](https://developer.android.com/jetpack/androidx/releases)

### Inferences
- **Modelo de dados Room (proposta):**
  - `Book(id, title, author, sourceApp, coverPageId, createdAt, cropRectNorm, pageCount, isFavorite, lastReadPageIndex, lastReadAt)`
  - `Page(id, bookId, index, filePath, width, height, bytes, hash64/hash256, ocrStatus, createdAt)` com índice único `(bookId, index)`
  - `PageText` como tabela `@Fts4(contentEntity = PageOcr::class)` para a busca, mais `PageOcr(pageId, text, blocksJson)`
  - `Collection(id, name)` + `BookCollectionCrossRef(bookId, collectionId)`
  - `Bookmark(id, bookId, pageIndex, note, createdAt)`
  - Progresso de leitura pode ficar em `Book` ou numa tabela `ReadingProgress` se for guardar histórico.
- **Arquivos:** `filesDir/books/{bookId}/pages/{index:04d}.webp` e `filesDir/books/{bookId}/thumbs/{index}.webp` (~300 px de largura). Armazenamento interno é privado, dispensa permissões e é apagado na desinstalação. Exportar para fora via SAF (`ACTION_CREATE_DOCUMENT`).
- **Pipeline:** `CaptureService` (foreground, mediaProjection) → `CaptureController` (StateFlow de estado: running/paused/finished; contagem; última miniatura) → `PageRepository.savePage()` (grava rápido) → enfileira `PostProcessWorker` (crop definitivo + reencode WebP lossless + thumbnail + hash) → `OcrWorker` (ML Kit) → atualiza FTS. Encadear com `WorkManager.beginUniqueWork(bookId, ...)`.
- **Leitor:** `HorizontalPager` + `ZoomableAsyncImage` (Telephoto + Coil 3) com subsampling para não carregar bitmaps de 1080×2400 inteiros na memória. Modo escuro/sépia via `ColorFilter`. Salvar `lastReadPageIndex` com debounce.
- **DI:** Hilt (padrão do Android, integra com WorkManager via `@HiltWorker` e com ViewModel). Koin é alternativa válida e mais leve. Versões do Hilt e do DataStore não foram confirmadas nesta pesquisa.
- **Navegação:** Navigation Compose 2.x com rotas type-safe é a escolha mais madura. Navigation 3 é estável (1.1.x) e serve para projetos novos que querem controle do back stack como estado. Qualquer uma serve. Para um app simples, 2.x tem mais material de referência.
- **Exportação:** PDF com `PdfDocument` (uma página PDF por imagem, `canvas.drawBitmap`, escrevendo em stream via SAF; processar uma página por vez para não estourar memória). CBZ = ZIP com as imagens numeradas (`ZipOutputStream`, `STORED` porque WebP já é comprimido). Ambos rodam num `ExportWorker` com notificação de progresso.

### Gaps
- Não confirmei as versões atuais de Hilt/Dagger, DataStore, Compose BOM e Kotlin. Consultar a página de versões do AndroidX e o Maven Central na implementação.
- A versão 3.5.0 do Coil vem de agregadores, não do changelog oficial.
- Não pesquisei alternativas ao Telephoto (ex.: `zoomimage` do panpf, que aparece como `zoomimage-compose-coil3` no klibs.io) em profundidade.
- Não verifiquei a assinatura atual da API `PdfDocument` nem limites de tamanho de página. O uso descrito é padrão, mas não foi checado em fonte nesta sessão.
