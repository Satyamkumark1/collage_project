import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import type { ReactNode } from "react";
import { useAuth } from "./context/AuthContext";
import { Register } from "./pages/Register";
import { Login } from "./pages/Login";
import { Library } from "./pages/Library";
import { Upload } from "./pages/Upload";
import { DocumentDetail } from "./pages/DocumentDetail";
import { Tutor } from "./pages/Tutor";
import { KeyPoints } from "./pages/KeyPoints";
import { Mcqs } from "./pages/Mcqs";
import { Flashcards } from "./pages/Flashcards";
import { Quizzes } from "./pages/Quizzes";
import { QuizAttempt } from "./pages/QuizAttempt";
import { QuizResult } from "./pages/QuizResult";
import { useQuery } from "@tanstack/react-query";
import { listDueFlashcards } from "./api/flashcards";

function ProtectedRoute({ children }: { children: ReactNode }) {
  const { status } = useAuth();

  if (status === "loading") {
    return (
      <div className="page" aria-busy="true" aria-live="polite">
        <span className="visually-hidden">Loading…</span>
        <div className="skeleton" style={{ height: 120 }} />
      </div>
    );
  }
  if (status === "unauthenticated") {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function NavBar() {
  const { status, logout } = useAuth();
  const navigate = useNavigate();

  const dueQuery = useQuery({
    queryKey: ["flashcards-due-count"],
    queryFn: () => listDueFlashcards(1),
    enabled: status === "authenticated",
    refetchInterval: 60_000,
  });
  const dueCount = dueQuery.data?.length ?? 0;

  return (
    <nav className="nav-bar">
      <Link to="/library" className="nav-brand">
        StudyFlow AI
      </Link>
      {status === "authenticated" && (
        <div className="nav-links">
          {dueCount > 0 && (
            <span className="badge" style={{ borderColor: "var(--highlight)", color: "var(--highlight)" }}>
              Flashcards due
            </span>
          )}
          <Link to="/upload" className="link-button">
            Upload
          </Link>
          <button
            type="button"
            className="link-button"
            onClick={() => {
              void logout().then(() => navigate("/login"));
            }}
          >
            Log out
          </button>
        </div>
      )}
    </nav>
  );
}

export default function App() {
  return (
    <>
      <NavBar />
      <Routes>
        <Route path="/register" element={<Register />} />
        <Route path="/login" element={<Login />} />
        <Route
          path="/library"
          element={
            <ProtectedRoute>
              <Library />
            </ProtectedRoute>
          }
        />
        <Route
          path="/upload"
          element={
            <ProtectedRoute>
              <Upload />
            </ProtectedRoute>
          }
        />
        <Route
          path="/documents/:id"
          element={
            <ProtectedRoute>
              <DocumentDetail />
            </ProtectedRoute>
          }
        />
        <Route
          path="/documents/:id/tutor"
          element={
            <ProtectedRoute>
              <Tutor />
            </ProtectedRoute>
          }
        />
        <Route
          path="/documents/:id/key-points"
          element={
            <ProtectedRoute>
              <KeyPoints />
            </ProtectedRoute>
          }
        />
        <Route
          path="/documents/:id/mcqs"
          element={
            <ProtectedRoute>
              <Mcqs />
            </ProtectedRoute>
          }
        />
        <Route
          path="/documents/:id/flashcards"
          element={
            <ProtectedRoute>
              <Flashcards />
            </ProtectedRoute>
          }
        />
        <Route
          path="/documents/:id/quizzes"
          element={
            <ProtectedRoute>
              <Quizzes />
            </ProtectedRoute>
          }
        />
        <Route
          path="/quizzes/:quizId/attempts/:attemptId"
          element={
            <ProtectedRoute>
              <QuizAttempt />
            </ProtectedRoute>
          }
        />
        <Route
          path="/quiz-attempts/:attemptId/result"
          element={
            <ProtectedRoute>
              <QuizResult />
            </ProtectedRoute>
          }
        />
        <Route path="/" element={<Navigate to="/library" replace />} />
        <Route path="*" element={<Navigate to="/library" replace />} />
      </Routes>
    </>
  );
}
