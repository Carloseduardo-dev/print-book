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

## Teste 7: 2026-11-11, Modo Turbo, livro todo e espaço (feito por Claude via ADB)

Captura de um app só (Minha Biblioteca, *Pré-cálculo*), qualidade Máxima.

| Teste | Resultado |
|---|---|
| Automático **Turbo**, limite de 100 | **100/100 páginas em 111 s (~1,1 s/página)**, contra ~3,5 s no modo normal. 0 falhas, 0 repetidas, todas 968×1282 |
| Automático Turbo, depois da regra anti-"deslize lento" | 69 páginas em 77 s (~1,1 s/página), todas 968×1282 |
| Folha de miniaturas (100 págs.) | Sem bolha, sem página borrada, sem repetida (menor diferença entre páginas seguidas: 5,3; limiar de repetida: 1,0) |
| Sonda da bolha | App único: diferença 0,00 → não esconde a bolha a cada página. Tela inteira: 21,8 → continua escondendo |
| Semi Turbo, viradas manuais a ~1/s | Páginas 172–185 seguidas, sem buraco e sem página no meio da animação. Viradas que o leitor ignorou (durante a animação anterior) não geram captura |
| Livro todo | OCR do rodapé leu "113 / 553" e "185 / 553". Bolha: "8/441 · ~14 min"; notificação "Página 120 de 553 · ~13 min restantes". O tempo converge para o ritmo real (~7 min para ~390 págs.) |
| Cartão "Captura concluída" | Aparece só com o print-book fora da tela; "Abrir livro" abre o Detalhe do livro certo |
| Modo Texto (30 págs. reais) | Máxima 193,5 KB · Econômica 102,7 KB · Texto cinza sem perda **58,3 KB** (−43% da Econômica, −70% da Máxima). Cinza com perda q85 ficou em 87 KB, então o Texto usa cinza **sem perda** |
| Liberar espaço (Texto) | **19,3 MB → 7,5 MB em 9 s**, com OCR rodando ao mesmo tempo; leitor e busca normais |

Bugs encontrados e corrigidos durante o teste:
- **Página capturada no meio da animação** (Semi Turbo): o fim da virada do Minha Biblioteca é lento, e 2 amostras parecidas bastavam. Agora a tela também não pode ter "escorregado" desde o início da sequência estável, e o Turbo usa 3 amostras (180 ms).
- **Virada perdida no Semi**: depois de capturar, o gatilho era armado com uma amostra nova da tela; se a pessoa já tinha virado, a página nova virava a referência. Agora arma com a página que foi capturada.
- **Miniatura vazia na bolha** no Turbo (troca a cada ~1 s): mostra a anterior até a nova carregar.
- **Toques no cartão final não chegavam** depois de encerrar o serviço em primeiro plano (One UI): o serviço continua em primeiro plano enquanto o cartão aparece.
- **OCR × compactação**: o OCR relê o nome do arquivo se a página foi trocada no meio do lote.
- **O leitor voltou sozinho** de ~p. 165 para p. 45 no meio de uma captura (provável sincronização com a nuvem do Minha Biblioteca). Agora o rodapé é relido a cada 10 páginas: se o leitor voltou, a captura pausa com aviso; se avançou, o progresso é recalculado.

Observação: em 3 de 32 tentativas, um toque `adb input tap` na bolha durante o automático Turbo não chegou à janela. O toque do adb é um evento injetado, como o gesto da Acessibilidade; um toque de dedo não é. Falta confirmar com o dedo.

## Teste 8: 2026-11-11, depois da revisão de código do Turbo (feito por Claude via ADB)

| Teste | Resultado |
|---|---|
| Automático Turbo + livro todo (app único) | 37 páginas em 44,7 s (~1,2 s/página) com a espera mínima de nitidez (150 ms) |
| Pausar e retomar no livro todo | Retomou na p. 512 (a última antes da pausa era a 511): sem repetida, alvo 42 = 553 − 512 + 1; bolha "12/42" com o leitor em 523/553 |
| Fim do livro | Parou exatamente em 553/553 ("Fim do livro: 42 páginas capturadas"); a última página foi gravada depois da mensagem (fila esvaziada) |
| Tela inteira + Texto (gravação mais lenta) | 27 páginas (527–553), ~2,1 s/página (esconde a bolha a cada página). Nenhuma com bolha ou aura; todas com o prefixo `g-` |
| Compactar Máxima → Texto | 14,5 MB → 6,0 MB; repetir a compactação: "já estava compactado", sem recodificar |

Correções desta rodada: aura acesa só entre capturas (nunca pela gravação em paralelo); falhas de gravação pausam o (semi)automático; envio para a fila não cancelável e dedupe atualizado só depois do envio; referência do gatilho tirada da página nítida; nitidez precisa ficar no máximo por um tempo mínimo; bolha sem ação enquanto a fila esvazia; resultados antigos do rodapé descartados; OCR do rodapé libera a imagem só no fim; compactação sob o lock de excluir livro/páginas e sem reprocessar páginas Texto; sonda da bolha na dúvida esconde; "Parar" funciona com o cartão de conclusão; ritmo e alvo corrigidos ao retomar.

## Teste 9: 2026-11-12, Turbo adaptativo e recuperação da página pulada (feito por Claude via ADB)

Edição pessoal, "Compartilhar um app", qualidade Máxima, Automático Turbo + livro todo. Livros de teste apagados depois; posições de leitura do Minha Biblioteca (p. 173) e do Kindle (posição 71) devolvidas.

| Teste | Resultado |
|---|---|
| Minha Biblioteca (Pré-cálculo), p. 173 em diante | **123 páginas em 158,7 s: 1,30 s/página** (mediana 1,29 s, p90 1,39 s, máx. 1,79 s). Indicador "N / 553" confirmado na 2ª página. 0 quadros no meio da virada, 0 páginas lentas, 0 puladas. Ritmo ajustado sozinho para ~1,12 s de intervalo (páginas mais lentas estabilizando em ~0,72 s) |
| Conferência pelo OCR (posição × número impresso) | 123 de 123 na sequência (173–295), nenhuma diferença |
| Pulo provocado (deslize extra via ADB depois das capturas 8 e 18) | Detectado "faltou a 304 (mostrou a 305)" e "faltou a 314": o app voltou, capturou, inseriu no lugar e avançou (~2 s cada). 55 páginas, 55 de 55 na sequência pelo OCR (296–350) |
| Kindle (amostra), posição 71 em diante | **28 páginas, 1,06 s/página**; o ritmo ficou no mínimo (900 ms, estabilização ~0,4 s). "Localização" não entra na conferência: nenhum falso alarme. Fim da amostra detectado sozinho |

Observação: forçar o fechamento do app (`am force-stop`) desliga o serviço de Acessibilidade; é preciso religá-lo.

## Teste 10: 2026-11-12, depois da revisão de código do Turbo adaptativo (feito por Claude via ADB)

Cobre o que o Teste 9 não cobriu. Livros de teste apagados; posições do Minha Biblioteca (p. 173) e do Kindle (posição 71) e os ajustes devolvidos.

| Teste | Resultado |
|---|---|
| **Tela inteira** (bolha e aura no quadro), Minha Biblioteca, Turbo + livro todo, pulos provocados | "Faltou a 181" e "faltou a 221": as duas **recuperadas** (antes da correção, a aura na comparação de antes/depois pausava a captura em toda recuperação). 61 páginas, 61 de 61 na sequência pelo OCR (173–233). **1,68 s/página** na tela inteira (era ~2,1 s no Teste 8) |
| Um dos deslizes extras não gerou pulo | O leitor ignorou o gesto (caiu durante a animação da virada): a contagem bate com a tela, sem página perdida |
| Kindle **sem** livro todo | Depois de 5 leituras sem "N / total" ("Localização" não é página), a leitura do rodapé foi desligada sozinha |

## Pendentes
- [x] Repetir o teste após a correção da bolha (Minha Biblioteca e Kindle)
- [x] Captura de **um app só**: a bolha não aparece. Quadro de 1080×2340.
- [x] Play Livros (100 páginas)
- [x] Conferir as coordenadas de recorte dos 3 apps

## Backlog

- **Modo Turbo:** feito (Teste 7). Capturar o livro inteiro **sem exibir** as páginas continua inviável: exigiria quebrar o DRM dos apps (art. 107 da Lei 9.610), o que está fora de questão. A opção "Capturar o livro todo" vira e mostra cada página.
- **Turbo mais rápido na "tela inteira" (registrado em 2026-11-11, para depois):** hoje cada página leva ~2,1 s na tela inteira contra ~1,2 s em "Compartilhar um app", porque a bolha precisa sumir de cada print. Ideias:
  - orientar no app, com o Turbo ligado: "para a velocidade máxima, escolha *Compartilhar um app*";
  - no automático, deixar a bolha escondida durante a sequência e mostrar só na pausa ou no fim (a pausa fica pela notificação).
- **Leitura em voz alta (adiada pelo usuário em 2026-11-11):**
  - O protótipo usava o `TextToSpeech` do Android (voz pt-BR offline `pt-br-x-afs-seanet-embedded`) sobre o texto do OCR, com avanço automático.
  - Bug encontrado antes de pausar: depois da primeira página, o avanço pulou da página 30 para a 33 sem ler a 31 e a 32 (as duas com texto de 1,8 a 2,8 mil caracteres). Causa ainda não identificada (callbacks `onDone`/`onStop` × `QUEUE_FLUSH`).
  - O código foi removido. As funções `TextTools.guessLanguage`/`forSpeech` ficaram, com testes, para reaproveitar.
