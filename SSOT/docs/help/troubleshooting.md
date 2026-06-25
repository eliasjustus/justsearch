# Troubleshooting

## Search returns no results

- Verify that you have added at least one folder to the Library
- Check that the folder contains supported file types (text, Markdown, PDF,
  DOCX, HTML, or source code)
- Wait for indexing to complete — new folders take a few seconds to index

## AI models not working

- Check Settings > AI to see if models are installed
- If "Install AI" shows an error, check your internet connection and retry
- On CPU-only machines (no NVIDIA GPU), model loading may take several
  minutes on first startup

## Backend not starting

If the application shows a connection error:

- Close and reopen JustSearch
- Check if another instance is already running
- On Windows, check Task Manager for `java.exe` processes from JustSearch

## Slow search

- Without AI models, search uses keyword matching which is fast but less
  accurate
- With AI models on CPU, first-time model loading takes several minutes;
  subsequent searches are faster
- For best performance, use a machine with an NVIDIA GPU (CUDA support)

## High disk usage

- AI models require approximately 8 GB of disk space
- The search index grows with the number of indexed documents
- Model data is stored in your user data directory
- To free space, uninstall AI models from Settings > AI
