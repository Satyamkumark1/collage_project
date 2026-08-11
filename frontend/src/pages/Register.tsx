import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, BACKEND_ORIGIN } from "../api/client";
import { GithubIcon, GoogleIcon } from "../components/BrandIcons";
import { useAuth } from "../context/AuthContext";

const CURRENT_YEAR = new Date().getFullYear();

export function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [birthYear, setBirthYear] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    // noValidate on the form below skips native required/min/max checks (needed so our own
    // error banner renders instead of the browser's), so an empty or out-of-range birth year
    // must be caught here before it reaches the API.
    const birthYearNumber = Number(birthYear);
    if (!birthYear || !Number.isInteger(birthYearNumber) || birthYearNumber < 1900
        || birthYearNumber > CURRENT_YEAR) {
      setError(`Please enter a birth year between 1900 and ${CURRENT_YEAR}.`);
      return;
    }

    setSubmitting(true);
    try {
      await register(email, password, name, birthYearNumber);
      navigate("/login", { state: { justRegistered: true } });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(errorMessageFor(err.code));
      } else {
        setError("Something went wrong. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: "#f8fafc", minHeight: "100vh", color: "#0f172a" }} className="light-theme">
      <div className="auth-page-wrapper">
        <div className="auth-split-container">
          {/* Left Side: Value Proposition & AI Study Visual */}
          <div className="auth-hero-section">
            <div className="auth-hero-badge">
              <span>🚀</span> Join StudyFlow AI Today
            </div>

            <h1 className="auth-hero-title">
              Unlock Your <span className="gradient-text">Full Potential</span>
            </h1>

            <p className="auth-hero-subtitle">
              Turn your class notes and textbooks into interactive flashcards, practice quizzes, and an instant AI study coach.
            </p>

            {/* AI & Study Hero Image */}
            <div className="auth-hero-image-wrapper">
              <img
                src="/study_ai_hero.png"
                alt="AI Study Workspace Illustration"
                className="auth-hero-image"
              />
            </div>

            {/* Feature Pills */}
            <div className="auth-pills-grid">
              <div className="auth-pill-item">
                <span>⚡</span>
                <span>Instant Document Parsing</span>
              </div>
              <div className="auth-pill-item">
                <span>🗂️</span>
                <span>Spaced Repetition Flashcards</span>
              </div>
              <div className="auth-pill-item">
                <span>🎯</span>
                <span>Adaptive MCQ Practice</span>
              </div>
              <div className="auth-pill-item">
                <span>🎓</span>
                <span>Smart AI Tutor & Planner</span>
              </div>
            </div>
          </div>

          {/* Right Side: Register Auth Form Card */}
          <div className="auth-card">
            <div className="auth-card-header">
              <h2>Create your account</h2>
              <p>Start mastering your subjects with AI in under 2 minutes</p>
            </div>

            {/* Social Auth Options — real full-page navigation, not fetch: OAuth redirects
                can't go through XHR/fetch. */}
            <div className="social-auth-group">
              <a href={`${BACKEND_ORIGIN}/oauth2/authorization/google`} className="social-btn">
                <GoogleIcon /> Continue with Google
              </a>
              <a href={`${BACKEND_ORIGIN}/oauth2/authorization/github`} className="social-btn">
                <GithubIcon /> Continue with GitHub
              </a>
            </div>

            <div className="auth-divider">
              <span>or sign up with email</span>
            </div>

            <form onSubmit={handleSubmit} className="stack" noValidate>
              {error && (
                <div className="error-banner" role="alert" style={{ borderRadius: "10px" }}>
                  <strong>Couldn&apos;t create account</strong>
                  <span>{error}</span>
                </div>
              )}

              <div className="field">
                <label htmlFor="name">Full Name</label>
                <div className="input-with-icon">
                  <span className="input-icon-prefix">👤</span>
                  <input
                    id="name"
                    className="input"
                    type="text"
                    placeholder="Alex Morgan"
                    autoComplete="name"
                    required
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                  />
                </div>
              </div>

              <div className="field">
                <label htmlFor="email">Email Address</label>
                <div className="input-with-icon">
                  <span className="input-icon-prefix">✉</span>
                  <input
                    id="email"
                    className="input"
                    type="email"
                    placeholder="alex@university.edu"
                    autoComplete="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </div>
              </div>

              <div className="field">
                <label htmlFor="password">Password</label>
                <div className="input-with-icon">
                  <span className="input-icon-prefix">🔒</span>
                  <input
                    id="password"
                    className="input"
                    type={showPassword ? "text" : "password"}
                    placeholder="At least 10 characters"
                    autoComplete="new-password"
                    required
                    minLength={10}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                  <button
                    type="button"
                    className="password-toggle-btn"
                    onClick={() => setShowPassword(!showPassword)}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                  >
                    {showPassword ? "👁️" : "🙈"}
                  </button>
                </div>
              </div>

              <div className="field">
                <label htmlFor="birthYear">Birth Year</label>
                <div className="input-with-icon">
                  <span className="input-icon-prefix">📅</span>
                  <input
                    id="birthYear"
                    className="input"
                    type="number"
                    inputMode="numeric"
                    placeholder="2003"
                    required
                    min={1900}
                    max={CURRENT_YEAR}
                    value={birthYear}
                    onChange={(e) => setBirthYear(e.target.value)}
                  />
                </div>
                <span className="hint" style={{ fontSize: "0.8rem", color: "#64748b" }}>
                  Required by DPDP law for age verification.
                </span>
              </div>

              <button
                type="submit"
                className="button button-block btn-primary-indigo"
                disabled={submitting}
                style={{ marginTop: "0.5rem" }}
              >
                {submitting ? "Creating account…" : "Create Free Account"}
              </button>
            </form>

            <p className="hint" style={{ marginTop: "1.75rem", textAlign: "center", fontSize: "0.9rem" }}>
              Already have an account?{" "}
              <Link to="/login" style={{ color: "#4f46e5", fontWeight: 600, textDecoration: "none" }}>
                Log in
              </Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

function errorMessageFor(code: string): string {
  switch (code) {
    case "AUTH_EMAIL_ALREADY_REGISTERED":
      return "That email is already registered. Try logging in instead.";
    case "VALIDATION_FAILED":
      return "Please check the fields above and try again.";
    default:
      return "Something went wrong. Please try again.";
  }
}

