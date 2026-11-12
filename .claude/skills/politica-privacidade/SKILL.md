---
name: politica-privacidade
description: Atualiza a Política de Privacidade do print-book (site/privacidade.html) e as notas de "Segurança dos dados" da Play Store a partir dos fatos reais do app. Use quando o app ganhar permissão, biblioteca, recurso de rede, backup, conta, analytics ou qualquer mudança no tratamento de dados, ou quando pedirem para revisar/gerar a política.
---

# Política de privacidade do print-book

A política tem que descrever **o que o código faz de verdade**. Nunca prometa algo que o app não garante: primeiro audite, depois escreva.

## 1. Auditar os fatos (antes de escrever)

Levante e anote cada item com a evidência (arquivo e linha):

1. **Permissões** — `app/src/main/AndroidManifest.xml` e os manifestos de cada flavor (`app/src/pessoal`, `app/src/loja`). Confirme o que entra no APK da loja: `./gradlew :app:processLojaReleaseManifest` e leia o manifesto mesclado em `app/build/intermediates/merged_manifest/`.
2. **Bibliotecas que falam com a rede ou coletam telemetria** — `app/build.gradle.kts` e `gradle/libs.versions.toml`. Para cada SDK de terceiros (ML Kit, Firebase, analytics, crash reporting, anúncios), leia a divulgação oficial de dados do fornecedor (para o ML Kit: https://developers.google.com/ml-kit/android-data-disclosure) e registre o que é enviado.
3. **Armazenamento** — onde ficam imagens, banco e preferências (`data/files/PageFileStore.kt`, `data/db`, `data/prefs`). Confirme que nada vai para armazenamento compartilhado.
4. **Backup e transferência** — `android:allowBackup`, `res/xml/data_extraction_rules.xml` e `res/xml/backup_rules.xml`. Só diga "fora do backup e da transferência" se as regras excluírem tudo.
5. **Rede própria** — procure por `HttpURLConnection`, `OkHttp`, `Retrofit`, `URL(`, `WebView` no código. Hoje: nenhuma chamada de rede própria.
6. **Recursos sensíveis por edição** — Acessibilidade e exportação de PDF existem só na edição `pessoal`; a política cobre a versão da Google Play (`loja`).
7. **Site** — hospedagem (Vercel), fontes externas (Google Fonts), cookies, formulários e analytics em `site/`.

## 2. Atualizar `site/privacidade.html`

- Mantenha a estrutura numerada (Quem somos → Contato) e o resumo em cartões no topo.
- Atualize as tabelas "Dados guardados no seu aparelho" e "Permissões do aplicativo".
- Toda nova biblioteca de terceiros entra em "Serviços de terceiros", com o que envia e o link oficial.
- **Atualize a data "Vigente desde" e incremente a versão** no topo; se houver novo tratamento de dados, avise também no app ou na página da Play.
- Linguagem: português claro, profissional, sem juridiquês desnecessário; base legal LGPD (Lei 13.709/2018).
- Nada de emojis comuns de celular; ícones são SVG de traço no estilo do site.
- Contato autorizado pelo desenvolvedor: e-mail **digitalbr316@gmail.com** e a página de issues do GitHub. Não publique nenhum outro dado pessoal sem autorização explícita.

## 3. Atualizar a ficha da loja

Em `docs/loja/play-store.md`, ajuste "Segurança dos dados (Data safety)" para bater com a política (o que é coletado, por quem, se é compartilhado, criptografia, finalidade) e o link da política.

## 4. Conferir

- Leia a página no celular: 390 px de largura sem rolagem horizontal (tabelas rolam dentro do próprio quadro) e no desktop com o índice lateral.
- Para prévia a 390 px no Chrome headless, use um iframe de 390 px (o headless força no mínimo 500 px de janela).
- Links internos: `index.html` ↔ `privacidade.html` (no rodapé e na seção de privacidade da landing).
