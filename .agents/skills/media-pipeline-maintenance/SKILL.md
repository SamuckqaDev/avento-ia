---
name: media-pipeline-maintenance
description: Diagnose, implement, or improve Avento image and video generation through ComfyUI and local models. Use for workflows, checkpoints, model selection, prompts, controls, asynchronous jobs, progress, preview, output storage, deletion, performance, and provider errors.
---

# Media Pipeline Maintenance

1. Identify the requested operation, selected provider/model, workflow file, input media, and frontend options.
2. Validate ComfyUI health, object info, required nodes, checkpoints, encoders, VAEs, and available memory before queueing.
3. Map typed Avento options into workflow node inputs; do not mutate workflow JSON with fragile global string replacement.
4. Treat generation as an asynchronous job with `runId`, progress, cancellation, terminal error, and media metadata.
5. Return only files actually produced and verified. Never turn model prose into a success response.
6. Store chat ownership and canonical paths in PostgreSQL; serve previews through authorized backend endpoints.
7. Delete database records, thumbnails, generated files, and provider leftovers when the owning chat is permanently deleted.
8. Test a cheap deterministic draft first, then quality, reference, refinement, and video paths separately.

Keep prompt enhancement optional and preserve the user's subject, count, composition, and reference intent.
