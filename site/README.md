# Site do print-book

Landing page estática (HTML, CSS e JS sem build), publicada na Vercel.

## Deploy na Vercel

1. Importe o repositório na Vercel.
2. Em **Root Directory**, escolha `site`.
3. Framework Preset: **Other** (sem comando de build; a saída é a própria pasta).

O `vercel.json` desta pasta faz a Vercel **pular o deploy quando nada em `site/` mudou** (`ignoreCommand`). Ou seja, commits só do app Android não geram deploy novo; qualquer mudança na página gera.

## Ver localmente

Abra `index.html` no navegador, ou sirva a pasta:

```bash
python -m http.server 8080 --directory site
```

As imagens ficam em `assets/` (cópias das capturas de `docs/screenshots/`).
