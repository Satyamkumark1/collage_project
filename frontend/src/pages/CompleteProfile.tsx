import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { completeBirthYear } from "../api/auth";
import { ApiError } from "../api/client";
import { useAuth } from "../context/AuthContext";

const CURRENT_YEAR = new Date().getFullYear();

export function CompleteProfile() {
  const { refreshUser } = useAuth();
  const navigate = useNavigate();
  const [birthYear, setBirthYear] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    const birthYearNumber = Number(birthYear);
    if (!birthYear || !Number.isInteger(birthYearNumber) || birthYearNumber < 1900
        || birthYearNumber > CURRENT_YEAR) {
      setError(`Please enter a birth year between 1900 and ${CURRENT_YEAR}.`);
      return;
    }

    setSubmitting(true);
    try {
      await completeBirthYear(birthYearNumber);
      await refreshUser();
      navigate("/library", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? "Something went wrong. Please try again." : "Network error. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: "#f8fafc", minHeight: "100vh", color: "#0f172a" }} className="light-theme">
      <div className="auth-page-wrapper">
        <div className="auth-card" style={{ margin: "4rem auto" }}>
          <div className="auth-card-header">
            <h2>One last step</h2>
            <p>India's DPDP law requires us to confirm your birth year before you use AI features.</p>
          </div>

          <form onSubmit={handleSubmit} className="stack" noValidate>
            {error && (
              <div className="error-banner" role="alert" style={{ borderRadius: "10px" }}>
                <strong>Couldn&apos;t save</strong>
                <span>{error}</span>
              </div>
            )}

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
                  autoFocus
                />
              </div>
            </div>

            <button
              type="submit"
              className="button button-block btn-primary-indigo"
              disabled={submitting}
              style={{ marginTop: "0.5rem" }}
            >
              {submitting ? "Saving…" : "Continue"}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
