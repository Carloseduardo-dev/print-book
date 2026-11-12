---
name: governanca-ia
description: Escreve ou atualiza a comunicação sobre o uso de IA no desenvolvimento do print-book (seção "Uso de IA" do README, textos do site ou da loja). Use quando pedirem para explicar, revisar ou atualizar como assistentes de codificação são usados no projeto, ou quando o processo de desenvolvimento mudar (novas etapas de revisão, testes, validação).
---

# Governança de IA no print-book

O objetivo é mostrar, com fatos, que a IA acelera o trabalho e que **as decisões e a responsabilidade são do autor** (Carlos Eduardo Ferreira). Maturidade aparece em processo e evidência, não em adjetivos.

## 1. Levantar os fatos antes de escrever

Confirme cada número e afirmação no repositório. Nada entra no texto sem evidência.

1. **Testes automatizados** — rode `./gradlew :app:testPessoalDebugUnitTest` e some os totais em `app/build/test-results/testPessoalDebugUnitTest/*.xml`. Use o número atual, sem arredondar para cima.
2. **Medições em aparelho** — `docs/medicoes-fase1.md`: quais testes existem, em qual aparelho, o que foi medido (tempo por página, páginas perdidas/repetidas, conferência da sequência por OCR).
3. **Fluxo de entrega** — confira no histórico do git (`git log --oneline`) que as mudanças passam por branch dedicado e chegam à `main` por merge depois da validação.
4. **Princípios fixos** — `FLAG_SECURE` nunca é contornado (README, código de detecção de tela preta); a política de privacidade é escrita a partir de auditoria do código (skill `politica-privacidade`).
5. **Ferramentas** — cite como "ferramentas de inteligência artificial, como Claude Code e Codex". Não cite versões nem modelos.

## 2. Tom e conteúdo

- Português claro e profissional, frases diretas, no formato comum de declaração de uso de IA. Nada de exagero ("revolucionário", "100% automatizado"), nem de modéstia que diminua o trabalho do autor.
- Terceira pessoa ("o autor"), como o resto do README.
- Deixe claro que o autor analisa, questiona e valida (não só "dá o prompt e aprova"): requisitos e critérios de aceite, perguntas sobre riscos, escolha dos apontamentos de revisão, testes pessoais em uso real, autorização do merge. Use exemplos reais do histórico quando ajudarem.
- Diga o que a IA faz (geração e refatoração de código, testes, revisão, investigação de bugs, documentação) e o que ela **não** decide (escopo, requisitos, arquitetura, critérios de aceite, o que vai para a `main`).
- Inclua os princípios: sugestões que conflitam com privacidade, com os princípios do projeto ou com a qualidade são descartadas.
- Não afirme garantias que o processo não dá (ex.: "sem bugs").
- Sem emojis comuns de celular. Sem dados pessoais além do nome do autor e do e-mail de contato autorizado (**digitalbr316@gmail.com**).

## 3. Onde fica

- **README**: seção `## Uso de IA`, depois de "Qualidade", com entrada no sumário. Formato: como a IA foi usada → análise e aprovação do autor (antes de implementar, na revisão, na validação, na entrega) → como a qualidade é garantida → princípios do projeto → responsabilidade do autor. Se o número de testes mudar, atualize também a seção "Qualidade".
- **Site e loja**: só com pedido explícito do autor. Se entrar, uma frase curta e natural, no mesmo tom.

## 4. Antes de publicar

O autor lê a prévia antes de qualquer commit no GitHub. Mostre o texto final completo e só faça commit/push depois da aprovação.
