import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ApiError, BACKEND_ORIGIN } from "../api/client";
import { GithubIcon, GoogleIcon, LockIcon, LogInIcon, MailIcon } from "../components/BrandIcons";
import { useAuth } from "../context/AuthContext";

export function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const justRegistered = Boolean((location.state as { justRegistered?: boolean } | null)?.justRegistered);
  const oauthFailed = new URLSearchParams(location.search).get("error") === "oauth_failed";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState<string | null>(oauthFailed ? "Sign-in with that provider failed. Please try again." : null);
  const [submitting, setSubmitting] = useState(false);

  // Demo interactive states
  const [flashcardFlipped, setFlashcardFlipped] = useState(false);
  const [selectedMcq, setSelectedMcq] = useState<number | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      navigate("/library");
    } catch (err) {
      if (err instanceof ApiError && err.code === "AUTH_INVALID_CREDENTIALS") {
        setError("Incorrect email or password.");
      } else {
        setError("Something went wrong. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: "#f8fafc", minHeight: "100vh", color: "#0f172a" }} className="light-theme">
      {/* 1. Hero & Split Auth Container */}
      <div className="auth-page-wrapper">
        <div className="auth-split-container">
          {/* Left Side: Hero Proposition & Image */}
          <div className="auth-hero-section">
            <div className="auth-hero-badge">
              <span>✨</span> Powered by Advanced AI
            </div>

            <h1 className="auth-hero-title">
              Master Any Subject <span className="gradient-text">10x Faster</span>
            </h1>

            <p className="auth-hero-subtitle">
              Upload notes, generate smart flashcards, solve practice MCQs, and get instant answers from your 24/7 AI Tutor.
            </p>

            {/* AI Hero Image */}
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

          {/* Right Side: Auth Form Card */}
          <div className="auth-card">
            <div className="signin-icon-badge">
              <LogInIcon />
            </div>

            <div className="auth-card-header">
              <h2>Welcome back</h2>
              <p>Enter your credentials to access your study library</p>
            </div>

            {justRegistered && (
              <div
                className="card"
                style={{
                  background: "#f0fdf4",
                  borderColor: "#bbf7d0",
                  color: "#166534",
                  marginBottom: "1.25rem",
                  borderRadius: "10px",
                  fontSize: "0.9rem",
                }}
              >
                ✓ Account created successfully — please log in.
              </div>
            )}

            <form onSubmit={handleSubmit} className="stack" noValidate>
              {error && (
                <div className="error-banner" role="alert" style={{ borderRadius: "10px" }}>
                  <strong>Couldn&apos;t log in</strong>
                  <span>{error}</span>
                </div>
              )}

              <div className="field">
                <label htmlFor="email">Email Address</label>
                <div className="signin-input-wrapper">
                  <span className="signin-input-icon">
                    <MailIcon />
                  </span>
                  <input
                    id="email"
                    className="signin-input"
                    type="email"
                    placeholder="student@university.edu"
                    autoComplete="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </div>
              </div>

              <div className="field">
                <label htmlFor="password">Password</label>
                <div className="signin-input-wrapper">
                  <span className="signin-input-icon">
                    <LockIcon />
                  </span>
                  <input
                    id="password"
                    className="signin-input"
                    type={showPassword ? "text" : "password"}
                    placeholder="••••••••"
                    autoComplete="current-password"
                    required
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    style={{ paddingRight: "2.6rem" }}
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

              <div className="auth-remember-row">
                <label htmlFor="remember-me">
                  <input
                    id="remember-me"
                    type="checkbox"
                    checked={rememberMe}
                    onChange={(e) => setRememberMe(e.target.checked)}
                  />
                  Remember me
                </label>
                <a
                  href="#forgot"
                  onClick={(e) => e.preventDefault()}
                  style={{ color: "#4f46e5", fontWeight: 600, textDecoration: "none" }}
                >
                  Forgot password?
                </a>
              </div>

              <button type="submit" className="signin-cta" disabled={submitting} style={{ marginTop: "0.5rem" }}>
                {submitting ? "Logging in…" : "Log in to StudyFlow AI"}
              </button>
            </form>

            <div className="signin-divider-dashed">
              <span>Or sign in with</span>
            </div>

            {/* Social Auth — real full-page navigation, not fetch: OAuth redirects can't go
                through XHR/fetch. */}
            <div className="signin-social-row">
              <a
                href={`${BACKEND_ORIGIN}/oauth2/authorization/google`}
                className="signin-social-icon-btn"
                aria-label="Continue with Google"
                title="Continue with Google"
              >
                <GoogleIcon />
              </a>
              <a
                href={`${BACKEND_ORIGIN}/oauth2/authorization/github`}
                className="signin-social-icon-btn"
                aria-label="Continue with GitHub"
                title="Continue with GitHub"
              >
                <GithubIcon />
              </a>
            </div>

            <p className="hint" style={{ marginTop: "1.75rem", textAlign: "center", fontSize: "0.9rem" }}>
              New to StudyFlow AI?{" "}
              <Link to="/register" style={{ color: "#4f46e5", fontWeight: 600, textDecoration: "none" }}>
                Create an account free
              </Link>
            </p>
          </div>
        </div>
      </div>

      {/* 2. Feature Section: Document Parsing */}
      <section className="landing-section">
        <div className="landing-section-header">
          <div className="landing-section-badge">📄 Instant Knowledge Ingestion</div>
          <h2 className="landing-section-title">Upload Anything, Understand Everything</h2>
          <p className="landing-section-subtitle">
            Drag and drop your lecture slides, PDFs, Word documents, or handwritten notes. StudyFlow AI extracts key concepts in seconds.
          </p>
        </div>

        <div className="feature-grid-3">
          <div className="feature-card">
            <div className="feature-icon-box">📑</div>
            <h3>PDF & Doc Extraction</h3>
            <p>Processes long textbooks and complex lectures into clean, structured chapter summaries instantly.</p>
          </div>

          <div className="feature-card">
            <div className="feature-icon-box">💡</div>
            <h3>Auto Key Points</h3>
            <p>Identifies core definitions, formulas, dates, and essential exam topics with precision citations.</p>
          </div>

          <div className="feature-card">
            <div className="feature-icon-box">🧬</div>
            <h3>Formula & Diagram Decoder</h3>
            <p>Understands technical equations, tables, and biological diagrams with step-by-step explanations.</p>
          </div>
        </div>
      </section>

      {/* 3. Interactive Demos: Flashcards & Practice MCQs */}
      <section className="landing-section" style={{ background: "#ffffff", borderRadius: "32px", border: "1px solid #e2e8f0" }}>
        <div className="landing-section-header">
          <div className="landing-section-badge">🧠 Active Recall Engine</div>
          <h2 className="landing-section-title">Retain 90% More Before Exam Day</h2>
          <p className="landing-section-subtitle">
            Experience smart flashcards with SM-2 spaced repetition algorithm and practice MCQs generated directly from your syllabus.
          </p>
        </div>

        <div className="demo-interactive-grid">
          {/* Flashcard Demo Box */}
          <div className="demo-card-box">
            <h3 style={{ fontSize: "1.15rem", fontWeight: 700, color: "#0f172a", marginBottom: "0.5rem" }}>
              🗂️ Interactive Flashcard Preview
            </h3>
            <p style={{ fontSize: "0.88rem", color: "#64748b", marginBottom: "1.25rem" }}>
              Click card below to flip between Question and Answer:
            </p>

            <button
              type="button"
              className="flashcard-flip-container"
              onClick={() => setFlashcardFlipped(!flashcardFlipped)}
              aria-pressed={flashcardFlipped}
            >
              {!flashcardFlipped ? (
                <div>
                  <div style={{ fontSize: "0.8rem", opacity: 0.8, textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: "0.5rem" }}>
                    Cell Biology • Question
                  </div>
                  <h4 style={{ fontSize: "1.3rem", fontWeight: 700, margin: 0 }}>
                    What is the primary function of Mitochondria?
                  </h4>
                  <div style={{ fontSize: "0.8rem", opacity: 0.7, marginTop: "1rem" }}>
                    🔄 Click to reveal answer
                  </div>
                </div>
              ) : (
                <div>
                  <div style={{ fontSize: "0.8rem", opacity: 0.8, textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: "0.5rem" }}>
                    Cell Biology • Answer
                  </div>
                  <h4 style={{ fontSize: "1.15rem", fontWeight: 600, margin: 0, lineHeight: 1.5 }}>
                    Generates cellular energy (ATP) through oxidative phosphorylation during aerobic respiration.
                  </h4>
                </div>
              )}
            </button>

            {flashcardFlipped && (
              <div className="flashcard-chip-group">
                <button type="button" className="flashcard-chip chip-again">🔴 Again (1m)</button>
                <button type="button" className="flashcard-chip chip-good">🔵 Good (1d)</button>
                <button type="button" className="flashcard-chip chip-easy">🟢 Easy (4d)</button>
              </div>
            )}
          </div>

          {/* Practice MCQ Demo Box */}
          <div className="demo-card-box">
            <h3 style={{ fontSize: "1.15rem", fontWeight: 700, color: "#0f172a", marginBottom: "0.5rem" }}>
              🎯 Instant Practice MCQ Quiz
            </h3>
            <p style={{ fontSize: "0.88rem", color: "#64748b", marginBottom: "1rem" }}>
              Sample quiz item generated from lecture notes:
            </p>

            <div style={{ background: "#f8fafc", padding: "1rem", borderRadius: "12px", marginBottom: "1rem", border: "1px solid #e2e8f0" }}>
              <strong style={{ fontSize: "0.95rem", color: "#0f172a", display: "block", marginBottom: "0.75rem" }}>
                Q: Which phase of mitosis involves chromosomes aligning along the metaphase plate?
              </strong>

              <div
                className={`mcq-option-item ${selectedMcq === 1 ? "correct" : ""}`}
                onClick={() => setSelectedMcq(1)}
              >
                <span>A. Metaphase</span>
                {selectedMcq === 1 && <span style={{ marginLeft: "auto" }}>✓ Correct!</span>}
              </div>

              <div
                className="mcq-option-item"
                onClick={() => setSelectedMcq(2)}
              >
                <span>B. Prophase</span>
              </div>

              <div
                className="mcq-option-item"
                onClick={() => setSelectedMcq(3)}
              >
                <span>C. Anaphase</span>
              </div>
            </div>

            {selectedMcq === 1 && (
              <div style={{ background: "#f0fdf4", border: "1px solid #bbf7d0", padding: "0.75rem", borderRadius: "10px", color: "#166534", fontSize: "0.85rem" }}>
                💡 <strong>AI Explanation:</strong> During metaphase, motor proteins push and pull chromosomes until they line up in the middle of the cell.
              </div>
            )}
          </div>
        </div>
      </section>

      {/* 4. AI Tutor Chat UI Section */}
      <section className="landing-section">
        <div className="landing-section-header">
          <div className="landing-section-badge">🤖 24/7 AI Personal Tutor</div>
          <h2 className="landing-section-title">Answers Sourced Directly from Your Notes</h2>
          <p className="landing-section-subtitle">
            Your AI Tutor answers with direct citations from your uploaded documents, grounded in the material you provide.
          </p>
        </div>

        <div className="ai-tutor-chat-wrapper">
          <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", borderBottom: "1px solid #e2e8f0", paddingBottom: "1rem", marginBottom: "1.25rem" }}>
            <div style={{ width: "12px", height: "12px", borderRadius: "999px", background: "#22c55e" }}></div>
            <strong style={{ fontSize: "1rem", color: "#0f172a" }}>StudyFlow AI Tutor (Active Session)</strong>
          </div>

          <div className="chat-message-bubble chat-bubble-user">
            Can you explain Bayes' Theorem in simple terms based on Chapter 4 of my Probability notes?
          </div>

          <div className="chat-message-bubble chat-bubble-ai">
            <strong style={{ color: "#4f46e5", display: "block", marginBottom: "0.25rem" }}>🎓 AI Tutor:</strong>
            Bayes' Theorem calculates the revised probability of an event based on new evidence.
            <br /><br />
            <strong>Formula:</strong> <code>P(A|B) = [P(B|A) * P(A)] / P(B)</code>
            <br /><br />
            <span style={{ fontSize: "0.82rem", color: "#64748b" }}>
              📌 Sourced from: <em>Probability_and_Stats_Ch4.pdf (Page 18, Paragraph 3)</em>
            </span>
          </div>
        </div>
      </section>

      {/* 5. Student Testimonials */}
      <section className="landing-section" style={{ background: "#ffffff", borderRadius: "32px", border: "1px solid #e2e8f0" }}>
        <div className="landing-section-header">
          <div className="landing-section-badge">⭐ Wall of Love</div>
          <h2 className="landing-section-title">Loved by 50,000+ Students Worldwide</h2>
          <p className="landing-section-subtitle">
            See how students from top universities are cutting their study time in half.
          </p>
        </div>

        <div className="testimonials-grid">
          <div className="testimonial-card">
            <div className="testimonial-author">
              <div className="author-avatar">SM</div>
              <div>
                <strong style={{ display: "block", color: "#0f172a", fontSize: "0.95rem" }}>Sophia Martinez</strong>
                <span className="univ-badge">Stanford Medical</span>
              </div>
            </div>
            <div style={{ color: "#f59e0b", marginBottom: "0.5rem" }}>★★★★★</div>
            <p style={{ color: "#475569", fontSize: "0.92rem", lineHeight: 1.5, margin: 0 }}>
              &ldquo;StudyFlow AI generated 200 medical flashcards from my Anatomy notes in 30 seconds. Passed my finals with flying colors!&rdquo;
            </p>
          </div>

          <div className="testimonial-card">
            <div className="testimonial-author">
              <div className="author-avatar" style={{ background: "#dbeafe", color: "#1d4ed8" }}>RK</div>
              <div>
                <strong style={{ display: "block", color: "#0f172a", fontSize: "0.95rem" }}>Rohan Kumar</strong>
                <span className="univ-badge">IIT Delhi / Computer Science</span>
              </div>
            </div>
            <div style={{ color: "#f59e0b", marginBottom: "0.5rem" }}>★★★★★</div>
            <p style={{ color: "#475569", fontSize: "0.92rem", lineHeight: 1.5, margin: 0 }}>
              &ldquo;The AI Tutor chat with note citations is insane. It solves complex algorithm questions step-by-step without hallucinating.&rdquo;
            </p>
          </div>

          <div className="testimonial-card">
            <div className="testimonial-author">
              <div className="author-avatar" style={{ background: "#fef3c7", color: "#b45309" }}>EL</div>
              <div>
                <strong style={{ display: "block", color: "#0f172a", fontSize: "0.95rem" }}>Emma Law</strong>
                <span className="univ-badge">Oxford University</span>
              </div>
            </div>
            <div style={{ color: "#f59e0b", marginBottom: "0.5rem" }}>★★★★★</div>
            <p style={{ color: "#475569", fontSize: "0.92rem", lineHeight: 1.5, margin: 0 }}>
              &ldquo;I used to spend 4 hours creating flashcards manually. Now StudyFlow does it instantly. Best tool for university students.&rdquo;
            </p>
          </div>
        </div>
      </section>

      {/* 6. Pricing Plans */}
      <section className="landing-section">
        <div className="landing-section-header">
          <div className="landing-section-badge">💎 Simple Pricing</div>
          <h2 className="landing-section-title">Invest in Your Grades</h2>
          <p className="landing-section-subtitle">
            Start for free. Upgrade anytime to unlock unlimited document uploads and priority AI processing.
          </p>
        </div>

        <div className="pricing-grid">
          <div className="pricing-card">
            <h3 style={{ fontSize: "1.25rem", fontWeight: 700, color: "#0f172a" }}>Starter Free</h3>
            <div className="pricing-amount">$0<span style={{ fontSize: "1rem", color: "#64748b", fontWeight: 400 }}> /month</span></div>
            <ul className="pricing-feature-list">
              <li>✓ 3 Document Uploads</li>
              <li>✓ 50 Auto Flashcards</li>
              <li>✓ 20 MCQ Practice Items</li>
              <li>✓ Basic AI Tutor Access</li>
            </ul>
            <button type="button" className="social-btn" style={{ width: "100%" }}>Start Free</button>
          </div>

          <div className="pricing-card featured">
            <div className="pricing-popular-badge">MOST POPULAR</div>
            <h3 style={{ fontSize: "1.25rem", fontWeight: 700, color: "#0f172a" }}>Student Pro</h3>
            <div className="pricing-amount" style={{ color: "#4f46e5" }}>$9<span style={{ fontSize: "1rem", color: "#64748b", fontWeight: 400 }}> /month</span></div>
            <ul className="pricing-feature-list">
              <li>✓ Unlimited Document Uploads</li>
              <li>✓ Unlimited Flashcards & MCQs</li>
              <li>✓ 24/7 Priority AI Tutor Chat</li>
              <li>✓ Study Planner & Analytics</li>
            </ul>
            <button type="button" className="button button-block btn-primary-indigo">Get Pro Student</button>
          </div>

          <div className="pricing-card">
            <h3 style={{ fontSize: "1.25rem", fontWeight: 700, color: "#0f172a" }}>Semester Pass</h3>
            <div className="pricing-amount">$19<span style={{ fontSize: "1rem", color: "#64748b", fontWeight: 400 }}> /6-months</span></div>
            <ul className="pricing-feature-list">
              <li>✓ Everything in Student Pro</li>
              <li>✓ Save 65% per semester</li>
              <li>✓ Group Study Sharing</li>
              <li>✓ Priority Customer Support</li>
            </ul>
            <button type="button" className="social-btn" style={{ width: "100%" }}>Get Semester Pass</button>
          </div>
        </div>
      </section>

      {/* 7. FAQ Section */}
      <section className="landing-section">
        <div className="landing-section-header">
          <div className="landing-section-badge">❓ FAQ</div>
          <h2 className="landing-section-title">Frequently Asked Questions</h2>
        </div>

        <div className="faq-list">
          <div className="faq-item">
            <div className="faq-question">Can I upload PDF textbooks or handwritten notes?</div>
            <p className="faq-answer">Yes! StudyFlow AI supports PDF, Word (.docx), PowerPoint (.pptx), and clear images of handwritten notes.</p>
          </div>

          <div className="faq-item">
            <div className="faq-question">How does spaced repetition flashcards work?</div>
            <p className="faq-answer">StudyFlow uses the SuperMemo SM-2 algorithm to schedule card reviews right before you are about to forget them, maximizing long-term memory retention.</p>
          </div>

          <div className="faq-item">
            <div className="faq-question">Are my notes private and secure?</div>
            <p className="faq-answer">Absolutely. Your notes are encrypted and only accessible to your personal account. They are never used to train public models.</p>
          </div>
        </div>
      </section>

      {/* 8. Bottom CTA Banner */}
      <div className="cta-banner">
        <h2>Ready to Ace Your Next Exam?</h2>
        <p>Join over 50,000+ students mastering their subjects 10x faster with AI.</p>
        <a
          href="#top"
          onClick={(e) => {
            e.preventDefault();
            window.scrollTo({ top: 0, behavior: "smooth" });
          }}
          className="button btn-primary-indigo"
          style={{ background: "#ffffff", color: "#4f46e5", fontSize: "1.05rem", padding: "0.9rem 2rem", textDecoration: "none" }}
        >
          Get Started Free Today 🚀
        </a>
      </div>

      {/* 9. Footer */}
      <footer className="landing-footer">
        <div style={{ maxWidth: "1180px", margin: "0 auto", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "1rem" }}>
          <div>© {new Date().getFullYear()} StudyFlow AI. All rights reserved.</div>
          <div style={{ display: "flex", gap: "1.5rem", flexWrap: "wrap" }}>
            <span>Privacy Policy</span>
            <span>Terms of Service</span>
            <span>Contact Support</span>
            <span>Academic Integrity</span>
          </div>
        </div>
      </footer>
    </div>
  );
}
