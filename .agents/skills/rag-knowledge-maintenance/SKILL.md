---
name: rag-knowledge-maintenance
description: Diagnose, implement, or improve Avento document knowledge and retrieval. Use for PDF and Office ingestion, MarkItDown, OCR, chunking, embeddings, Redis VectorStore, metadata, retrieval thresholds, citations, chat-scoped knowledge, deletion, and hallucination reduction.
---

# RAG Knowledge Maintenance

1. Verify the source file exists, is authorized, readable, and converted successfully before indexing.
2. Normalize extracted text while preserving headings, page/source metadata, and stable document identity.
3. Chunk by semantic boundaries with bounded overlap; do not index empty, duplicate, or navigation-only fragments.
4. Generate embeddings with the configured model and store reconstructible vectors plus ownership metadata in Redis.
5. Filter retrieval by user, chat, workspace, and document scope before ranking.
6. Tune top-K and similarity thresholds with representative questions; an empty retrieval result must stay empty instead of becoming invented context.
7. Tell the model which passages are evidence and require source-aware answers when document knowledge is used.
8. Test ingestion, re-ingestion, retrieval, no-match behavior, cross-user isolation, and deletion of vectors with the chat/document.

Keep original documents and durable metadata under backend ownership; Redis remains rebuildable acceleration.
