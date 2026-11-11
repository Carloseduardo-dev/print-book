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

## Pendentes
- [x] Repetir o teste após a correção da bolha (Minha Biblioteca e Kindle)
- [x] Captura de **um app só**: a bolha não aparece. Quadro de 1080×2340.
- [ ] Play Livros
- [ ] Conferir as coordenadas de recorte dos 3 apps
