import { useMutation } from "@tanstack/react-query";
import { useRef, useState, type DragEvent } from "react";
import { useNavigate } from "react-router-dom";
import { uploadDocument } from "../api/documents";
import { ApiError } from "../api/client";

const ACCEPTED_EXTENSIONS = [".pdf", ".txt", ".md", ".docx", ".pptx"];

export function Upload() {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);

  const mutation = useMutation({
    mutationFn: (file: File) => uploadDocument(file),
    onSuccess: (result) => {
      navigate(`/documents/${result.documentId}`);
    },
  });

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragging(false);
    const file = event.dataTransfer.files[0];
    if (file) {
      setSelectedFile(file);
    }
  }

  function handleSubmit() {
    if (selectedFile) {
      mutation.mutate(selectedFile);
    }
  }

  return (
    <div className="page page-narrow">
      <h1>Upload notes</h1>
      <p className="hint">PDF, TXT, MD, DOCX, or PPTX — up to 25 MB.</p>

      {mutation.isError && (
        <div className="error-banner" role="alert">
          <strong>Upload failed</strong>
          <span>{errorMessageFor(mutation.error)}</span>
        </div>
      )}

      <div
        className={`dropzone${dragging ? " dragging" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <p>
          <label htmlFor="file-input" className="link-button" style={{ textDecoration: "underline" }}>
            Choose a file
          </label>{" "}
          or drag it here
        </p>
        <input
          id="file-input"
          ref={inputRef}
          type="file"
          accept={ACCEPTED_EXTENSIONS.join(",")}
          className="visually-hidden"
          onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
        />
        {selectedFile && (
          <p className="hint" aria-live="polite">
            Selected: {selectedFile.name} ({(selectedFile.size / 1024).toFixed(0)} KB)
          </p>
        )}
      </div>

      <button
        type="button"
        className="button button-primary button-block"
        disabled={!selectedFile || mutation.isPending}
        onClick={handleSubmit}
        style={{ marginTop: "1.5rem" }}
      >
        {mutation.isPending ? "Uploading…" : "Upload"}
      </button>
    </div>
  );
}

function errorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "FILE_TYPE_UNSUPPORTED":
        return "That file type isn't supported — try PDF, TXT, MD, DOCX, or PPTX.";
      case "FILE_TOO_LARGE":
        return "That file is too large (25 MB limit).";
      case "AUTH_GUARDIAN_CONSENT_REQUIRED":
        return "Uploading requires guardian consent for accounts under 18.";
      case "QUOTA_UPLOADS_EXCEEDED":
        return "You've hit this month's upload limit.";
      default:
        return "Something went wrong. Please try again.";
    }
  }
  return "Something went wrong. Please try again.";
}
