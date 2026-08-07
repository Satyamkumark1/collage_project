# Library & Storage

## Storage provider

The spec's target is Cloudinary with `resource_type=raw`, `type=authenticated`, and signed,
time-limited delivery URLs — a raw file/document store, not the image/video CDN Cloudinary
defaults to (constraint #7 in
[00-product-and-constraints.md](00-product-and-constraints.md)).

**This phase:** a `StorageProvider` interface (`store(bytes, key) -> storageKey`,
`retrieve(storageKey) -> InputStream`, `delete(storageKey)`) with a `LocalDiskStorageProvider`
implementation writing under `STORAGE_LOCAL_ROOT` (env var, fail-fast if unset or unwritable),
namespaced `users/{userId}/{documentId}/`. Swapping in Cloudinary later is a new implementation
of the same interface plus a config flag — not a rewrite of any calling code.

## Upload flow — deviation

The spec's flow avoids proxying file bytes through the JVM: `POST /documents/upload-intent`
returns signed Cloudinary upload params, the browser uploads directly to Cloudinary, then
`POST /documents` confirms + enqueues ingestion.

**This phase:** since storage is local disk, not a cloud CDN, that two-step dance doesn't buy
anything yet — so upload is a direct `POST /documents` multipart request (see
[03-api-and-errors.md](03-api-and-errors.md)). This is a **documented deviation**; when Cloudinary
is wired in, the two-step presigned flow replaces this endpoint and the frontend upload call
changes accordingly.

Server-side, regardless of transport:
1. Validate declared filename, MIME, and size against a configured limit.
2. Independently sniff the actual file signature (magic bytes) — never trust the client's
   declared MIME type.
3. Compute `content_sha256`; if a non-deleted document with the same `(owner_id, content_sha256)`
   already exists, return that document instead of creating a duplicate (avoids re-billing
   ingestion for a re-upload of the same file).
4. Store via `StorageProvider`, create the `documents` row (`status = UPLOADED`), enqueue a
   `DOCUMENT_INGEST` job (see [07-jobs-and-async.md](07-jobs-and-async.md)).

## Limits (this phase)

A single hardcoded sane limit set lives in config (billing/plan-tiered limits are deferred — see
[12-billing-and-quotas.md](12-billing-and-quotas.md)): reject over-limit at request time, before
storing anything. Supported `file_type` this phase: `PDF`, `TXT`, `MD` (`DOCX`/`PPTX` deferred,
see [09-rag.md](09-rag.md) §Parsing).
