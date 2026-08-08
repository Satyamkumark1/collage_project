import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { useAuth } from "../context/AuthContext";

const CURRENT_YEAR = new Date().getFullYear();

export function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [birthYear, setBirthYear] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(email, password, name, Number(birthYear));
      navigate("/login", { state: { justRegistered: true } });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(errorMessageFor(err.code, err.message));
      } else {
        setError("Something went wrong. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page page-narrow">
      <h1>Create your account</h1>
      <p className="hint">
        Turn your own notes into summaries, MCQs, flashcards, and a tutor that only answers from
        what you uploaded.
      </p>

      <form onSubmit={handleSubmit} className="stack" noValidate>
        {error && (
          <div className="error-banner" role="alert">
            <strong>Couldn&apos;t create your account</strong>
            <span>{error}</span>
          </div>
        )}

        <div className="field">
          <label htmlFor="name">Full name</label>
          <input
            id="name"
            className="input"
            type="text"
            autoComplete="name"
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            className="input"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            className="input"
            type="password"
            autoComplete="new-password"
            required
            minLength={10}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <span className="hint">At least 10 characters.</span>
        </div>

        <div className="field">
          <label htmlFor="birthYear">Birth year</label>
          <input
            id="birthYear"
            className="input"
            type="number"
            inputMode="numeric"
            required
            min={1900}
            max={CURRENT_YEAR}
            value={birthYear}
            onChange={(e) => setBirthYear(e.target.value)}
          />
          <span className="hint">Indian law (DPDP Act) requires this for users under 18.</span>
        </div>

        <button type="submit" className="button button-primary button-block" disabled={submitting}>
          {submitting ? "Creating account…" : "Create account"}
        </button>
      </form>

      <p className="hint" style={{ marginTop: "1.5rem" }}>
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </div>
  );
}

function errorMessageFor(code: string, fallback: string): string {
  switch (code) {
    case "AUTH_EMAIL_ALREADY_REGISTERED":
      return "That email is already registered. Try logging in instead.";
    case "VALIDATION_FAILED":
      return "Please check the fields above and try again.";
    default:
      return fallback;
  }
}
