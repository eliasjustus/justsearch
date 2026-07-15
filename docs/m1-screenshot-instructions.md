# m1: Hero screenshots for the README — what to capture

The README reserves a hero-visual slot (HTML comment near the top). An automated attempt to
capture presentable screenshots was made and abandoned: the installed app's UI could only be
captured off-screen in a "Connection Error" retry state (synthetic clicks don't reach the
WebView2 surface without focus), and the built `modules/ui-web/dist` bundle renders blank
outside the Tauri shell against the v0.1.0 backend (surface-catalog API drift). Manual capture
takes two minutes and will look better anyway.

## The two shots to take

1. **`docs/assets/hero-search.png` — search results over a real-looking corpus.**
   Launch the installed app, index a folder with a few dozen mixed PDFs/Office/Markdown files
   (nothing private — a folder of public papers or the repo's own `docs/` works), type a
   *semantic* query where keyword search would fail (e.g. querying a concept phrased
   differently than the documents phrase it), and capture the results list showing titles,
   snippets, and facets. Window at default size (1024x700) or wider; light or dark theme —
   whichever renders cleaner.

2. **`docs/assets/hero-answer.png` (or a short GIF `hero-demo.gif`) — a cited AI answer.**
   With the models installed and Online mode up, ask a question in the assistant pane and
   capture the grounded answer *with its citation chips visible* — the citations are the
   product's honest differentiator, so they must be in frame.

## Capture mechanics (Windows)

- Win+Shift+S (Snipping Tool) → window snip of the JustSearch window, save as PNG.
- Keep PNGs under ~500 KB if possible (resize to ~1600px wide).
- Put both files in `docs/assets/` (create the dir), then replace the
  `HERO VISUAL PLACEHOLDER` comment at the top of `README.md` with:

```html
<p align="center">
  <img src="docs/assets/hero-search.png" width="820"
       alt="JustSearch: hybrid search over a local folder">
</p>
<p align="center">
  <img src="docs/assets/hero-answer.png" width="820"
       alt="A cited, on-device AI answer grounded in local documents">
</p>
```

Delete this file once the assets are wired in.
