# AI Features

JustSearch includes optional AI capabilities that run entirely on your
machine. No data is sent to external servers.

## Semantic search

After installing AI models, search understands the meaning of your query,
not just keywords. Searching for "how to deploy" finds documents about
"release process" or "production setup" even without those exact words.

## Named entity recognition (NER)

JustSearch automatically identifies people, organizations, locations, and
other entities in your documents. This improves search accuracy for queries
like "documents mentioning Acme Corp".

## Query expansion

Your search query is automatically expanded with related terms. A search
for "auth" also considers "authentication", "authorization", and "login".

## Cross-encoder reranking

Search results are reranked using a cross-encoder model that compares each
result directly against your query for more accurate relevance scoring.

## SPLADE sparse retrieval

SPLADE (Sparse Lexical and Expansion) generates learned sparse
representations of documents, bridging the gap between keyword and
semantic search.

## Ask your documents

With AI models installed, you can ask questions in natural language and get
answers drawn from your files — each backed by **citations** to the source
passages, so you can verify every claim. This is the primary AI mode.

## Chat, summarize, and extract

Beyond Q&A, the same local model powers several modes in one window: a
free-chat mode, document **summarization**, and structured **extraction** —
all running on your machine.

## Built-in agent

For multi-step requests, an optional agent can search your index, browse
indexed folders, request ingestion of new files, and perform approved file
operations. Anything that makes a change is gated — you approve it, and it is
logged.

## Use JustSearch from other AI tools (MCP)

JustSearch can act as a private retrieval backend for external AI agents over
the **Model Context Protocol (MCP)**. Tools like Claude Code, Cursor, and
Claude Desktop can connect to your running instance and search, retrieve
context, and ask questions over your files — without your data leaving your
machine.

## Requirements

- AI models require approximately 8 GB of disk space
- GPU acceleration (NVIDIA CUDA) is recommended for faster inference
- CPU-only inference works but is slower
