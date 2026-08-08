import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import type { ReactNode } from "react";
import { useAuth } from "./context/AuthContext";
import { Register } from "./pages/Register";
import { Login } from "./pages/Login";
import { Library } from "./pages/Library";
import { Upload } from "./pages/Upload";
import { DocumentDetail } from "./pages/DocumentDetail";

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

  return (
    <nav className="nav-bar">
      <Link to="/library" className="nav-brand">
        StudyFlow AI
      </Link>
      {status === "authenticated" && (
        <div className="nav-links">
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
        <Route path="/" element={<Navigate to="/library" replace />} />
        <Route path="*" element={<Navigate to="/library" replace />} />
      </Routes>
    </>
  );
}
