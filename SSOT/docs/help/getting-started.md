# Getting Started with JustSearch

JustSearch is a local-first knowledge engine that indexes your files and lets
you search across them using natural language — and, with the optional AI
models, ask questions and get answers backed by citations. Everything stays on
your machine.

## Adding files to search

1. Open JustSearch
2. Go to Settings > Library
3. Click "Add folder" and select a folder containing your documents
4. JustSearch will index the files automatically

Supported formats include plain text, Markdown, PDF, Word documents (DOCX),
HTML, and source code files.

## Searching

Type your query in the search bar. JustSearch supports:

- **Natural language queries**: "meeting notes from last week about budget"
- **Keyword search**: specific terms like filenames or code identifiers
- **Quoted phrases**: "exact phrase match"

Results are ranked by relevance. Without AI models installed, search uses
keyword matching (BM25). With AI models, search uses hybrid retrieval
combining keywords with semantic understanding.

## Installing AI models

For better search quality, install the AI models:

1. Go to Settings > AI
2. Click "Install AI"
3. Wait for the download to complete (~8 GB)

AI models enable semantic search, named entity recognition, and query
expansion — and unlock asking questions of your documents with cited answers,
summarization, and connecting external AI tools over MCP (see AI Features).
All inference runs locally on your machine.
