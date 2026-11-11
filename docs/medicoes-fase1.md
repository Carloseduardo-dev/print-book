# Medições da Fase 1 (spike de captura)

Aparelho: Galaxy A56 (SM-A566E), Android 17 / API 37, tela 1080×2340.

## Teste 1: 2026-11-10, Minha Biblioteca (*Álgebra Linear*), tela inteira

| Métrica | Resultado |
|---|---|
| Páginas capturadas num único consentimento | 23, sem nenhuma falha |
| Início do FGS a partir do resultado do consentimento | Permitido (`uidState: TOP`) |
| Encode WebP lossless (esforço 75) | 113–203 ms por página, média de ~145 ms |
| Tamanho por página (tela inteira, sem recorte) | 293–789 KB, média de ~550 KB → **~55 MB por 100 páginas** |
| Legibilidade | Ótima: texto pequeno e itálico nítidos |
| Quadro preto | Nenhum |

### Problemas encontrados
1. **A bolha apareceu na captura** em parte das páginas (ex.: 0012), mas não em todas (ex.: 0002).
   - Causa: o app aceitava quadros que já estavam em trânsito antes de a bolha sair da composição.
   - Correção: esconder via `alpha` da janela + `INVISIBLE` + `FLAG_NOT_TOUCHABLE`, esperar 3 vsyncs (Choreographer) + 32 ms e só então aceitar quadros novos.
   - Status: **resolvido** (teste 2: nenhuma das 26 páginas mostra a bolha).
2. **Chip vermelho de compartilhamento de tela** do Android 15+ na barra de status, e a barra do leitor no topo e embaixo.
   - Ambos saem com o recorte por perfil (Fase 2).
3. **Tamanho acima da estimativa** do relatório (11–30 MB por 100 páginas).
   - Esperado sem recorte: a página do Minha Biblioteca ocupa só ~60% da tela.
   - Reavaliar depois do recorte. Se continuar alto, testar WebP lossy com qualidade 90+.

## Teste 2: 2026-11-10, após a correção da bolha (3 sessões, 26 páginas)

| Sessão | App / livro | Páginas | Tamanho médio | Encode |
|---|---|---|---|---|
| 1 | Minha Biblioteca, *Cálculo vol. 2* (leitor em modo imersivo, sem barras) | 10 | ~420 KB (página com muito branco) | 73–171 ms |
| 2 | Kindle, *Criando Microsserviços* (pt, tema escuro) | 10 | **~180 KB** | 96–149 ms |
| 3 | Kindle, *Clean Code* (en) | 6 | ~290 KB (com ilustrações) | 101–198 ms |

- **Bolha:** ausente em 26/26 páginas ✅
- **Quadro:** 1080×2340 em todas as sessões. Nenhum quadro preto, nenhuma falha.
- **Kindle:** ~16–18 MB por 100 páginas, dentro da estimativa. O Kindle já fica em tela cheia, então só o rodapé "Localização X de Y · %" precisa de recorte.
- **Minha Biblioteca no modo imersivo** (barras escondidas com um toque): a página aparece centralizada, com margens brancas grandes. Recortar a área da página deve reduzir bem o tamanho.

## Teste 3: 2026-11-11, Fase 2 de ponta a ponta (feito por Claude via ADB)

- **Captura de um app só (Kindle):** quadro 1080×2340. A bolha não aparece (fica fora do app compartilhado).
- **Semiautomático:** 1 toque em ▶ + 6 viradas de página no Kindle (intervalo de 2,5 s) → **7 páginas**, nenhuma repetida, nenhuma perdida.
- **Recorte do perfil Kindle:** 1080×2223 (rodapé "Localização" removido). 142–333 KB por página, encode de 93–163 ms.
- **Leitor:** zoom +/−/ajustar, deslize entre páginas, progresso salvo (página 3 de 7 aparece no Detalhe e no Início).
- **Bug encontrado e corrigido: travamento na One UI.** O `Choreographer` é pausado quando o app fica em segundo plano, o que acontece quando a bolha é escondida. Esperar vsyncs travava a captura para sempre. Agora a espera é por tempo (110 ms) + chegada de quadros, com timeout de 2 s.
- **Bug corrigido:** um livro concluído continuava "selecionado" na aba Capturar e escondia o campo de nome.

## Teste 4: 2026-11-11, 100 páginas por app (semiautomático, feito por Claude via ADB)

Roteiro: captura de um app só, ▶ na bolha e 99 deslizes para a esquerda a cada 2,6 s.

| App / livro | Salvas | Repetidas | Dimensão | Tamanho (100 págs.) | Média por página |
|---|---|---|---|---|---|
| Minha Biblioteca, *Cálculo vol. 2* | **100/100** | 0 | 968×1268 em todas (barras + margens removidas) | **18,5 MB** | 181 KB (7–280) |
| Play Livros, *Jetpack Compose Essentials* (amostra) | **100/100** | 0 | 1080×2246 em todas (rodapé removido) | **32,4 MB** | 317 KB (183–734) |

- Nenhum par de páginas consecutivas parecido demais: a menor diferença média ficou entre 6,4 e 8,3, contra o limiar de duplicata de 1,0. Ou seja, cada virada gerou exatamente uma captura.
- Folha de miniaturas das 200 páginas: sem bolha, sem barras do leitor, sem quadro preto.
- Legibilidade em resolução real: tabela do método de Euler (subscritos e frações) e texto do Play Livros nítidos.
- O Play Livros fica maior porque guarda a tela inteira em 1080 px de largura com fundo escuro e figuras. Se o espaço virar problema, testar WebP lossy com qualidade 90 (Fase 3).

Bugs corrigidos durante o teste:
- O nome do livro continuava no campo depois de iniciar a captura (agora é limpo).
- O botão "Iniciar captura" fica abaixo da dobra quando há livros em captura. Está ok com rolagem, mas é candidato a melhoria de layout.

## Teste 5: 2026-11-11, Fase 3, modo AUTOMÁTICO (feito por Claude via ADB, um único toque em ▶)

Serviço de Acessibilidade ativado via `settings put secure enabled_accessibility_services`. Deslize de 0,88w → 0,22w em y=0,70h, intervalo de 2,5 s + variação aleatória de 0–0,7 s.

| Teste | Resultado |
|---|---|
| Minha Biblioteca, limite de 100 | **100/100 em 347 s (~3,5 s/página)**, 0 duplicatas, todas 968×1268, 19,4 MB. Parou sozinho: "Limite de páginas da sessão atingido" |
| Play Livros até o fim da amostra (Econômica) | **145 páginas em 521 s**, parou sozinho em "Fim do livro" |
| Qualidade Econômica (WebP 90) | **~170 KB/página contra 317 KB na Máxima (−46%)**. Texto e código nítidos em resolução real. Encode de ~220 ms (contra ~145 ms) |
| Fim da amostra, depois da correção | 25 páginas. Parou ao detectar a troca de tela `ReadingActivity → ArbitraryFragmentActivity`, **sem salvar a tela de compra** |
| Pausa ao sair do leitor | HOME aos 15 s → "Captura pausada: você saiu do app de leitura" (5 páginas salvas) |
| Teclas de volume no leitor | 3× volume baixo + 1× volume alto: página 1 → 3. O volume do sistema não mudou |
| Flavor `loja` | 0 referências a `BIND_ACCESSIBILITY_SERVICE` no manifesto final |

Bug encontrado e corrigido: no fim da amostra, o Play Livros abre a tela de compra (mesmo pacote, outra Activity) e a primeira versão capturava essa tela como página. Agora o serviço acompanha a Activity em primeiro plano, e o automático encerra sem capturar quando ela muda.

## Teste 6: 2026-11-11, Fase 4, OCR, busca e PDF (feito por Claude via ADB)

| Teste | Resultado |
|---|---|
| Migração Room 1→2 (instalado por cima) | Livros (99 MB), progresso e coleções preservados |
| OCR em segundo plano (ML Kit embutido) | **482/482 páginas indexadas, 0 falhas**, ~1,5–1,7 s/página (backfill automático ao abrir o app) |
| Busca na Biblioteca: "euler" | 11 páginas, com o trecho e o termo destacado. Tocar em "pág. 30" abre a página da tabela do método de Euler |
| Busca sem acento: "aproximacao" (no livro) | Encontra "aproximação" (FTS4 `unicode61 remove_diacritics`). Tocar no resultado leva à página 10/100 |
| Exportar PDF ("Auto Play fim2", 25 págs.) | `Download/Auto Play fim2.pdf`, **25 páginas, 9,2 MB**, abre e renderiza legível |
| Leitura em voz alta | Removida a pedido do usuário (ver backlog) |

Decisão: a permissão INTERNET foi mantida (vem do ML Kit; uso futuro possível). Nenhuma função atual depende de rede.

## Pendentes
- [x] Repetir o teste após a correção da bolha (Minha Biblioteca e Kindle)
- [x] Captura de **um app só**: a bolha não aparece. Quadro de 1080×2340.
- [x] Play Livros (100 páginas)
- [x] Conferir as coordenadas de recorte dos 3 apps

## Backlog

- **Modo Turbo (ideia do usuário, 2026-11-11):** captura automática em ~1 s por página, em vez de ~3,5 s.
  - Virar assim que a página estabilizar, sem intervalo fixo.
  - Gravar/encodar em paralelo com a próxima virada.
  - Intervalo mínimo menor.
  - Estimativa: 100 páginas em ~2 min. Opcional e desligado por padrão, porque viradas muito rápidas aumentam o risco de detecção pelo serviço de leitura.
  - Capturar o livro inteiro sem exibir as páginas é inviável: exigiria quebrar o DRM dos apps (art. 107 da Lei 9.610), o que está fora de questão.
- **Leitura em voz alta (adiada pelo usuário em 2026-11-11):**
  - O protótipo usava o `TextToSpeech` do Android (voz pt-BR offline `pt-br-x-afs-seanet-embedded`) sobre o texto do OCR, com avanço automático.
  - Bug encontrado antes de pausar: depois da primeira página, o avanço pulou da página 30 para a 33 sem ler a 31 e a 32 (as duas com texto de 1,8 a 2,8 mil caracteres). Causa ainda não identificada (callbacks `onDone`/`onStop` × `QUEUE_FLUSH`).
  - O código foi removido. As funções `TextTools.guessLanguage`/`forSpeech` ficaram, com testes, para reaproveitar.
