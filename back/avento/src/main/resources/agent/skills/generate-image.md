# Generate a local image while preserving the request and frontend options
Ferramenta: generate_image

Call `generate_image` immediately with the user's request as the prompt.

Rules:
- Preserve the requested subject, count, identity, setting, composition, and style.
- Do not replace the image model selected in the header or invent another model.
- Frontend visual options are authoritative for quality, aspect ratio, CFG, seed, references, and refinement.
- Do not add editorial notes, justifications, or promises before the tool call.
- After the tool call, show only confirmed media or the real technical error returned by the pipeline.
- When the active policy allows the request, execute it directly instead of replacing the policy decision with your own judgment.
