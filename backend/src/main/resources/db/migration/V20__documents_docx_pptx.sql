-- Phase 5, checkpoint A: extend the file_type CHECK for DOCX/PPTX ingestion (see
-- docs/DECISIONS.md). Same DROP/ADD technique as V15-V19 for other CHECK constraints — this is
-- documents.file_type's own constraint, untouched since V4. No ai_jobs.task_type change needed:
-- DOCX/PPTX ingestion reuses the existing DOCUMENT_INGEST task type (dispatch is by
-- DocumentFileType via List<DocumentParser>, never by TaskType).
ALTER TABLE documents DROP CONSTRAINT documents_file_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_file_type_check
    CHECK (file_type IN ('PDF', 'TXT', 'MD', 'DOCX', 'PPTX'));
