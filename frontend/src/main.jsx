import React, { useState, useEffect, useRef } from "react";
import { createRoot } from "react-dom/client";
import {
  BookOpen,
  Plus,
  Search,
  FileText,
  MessageSquare,
  ArrowUp,
  Upload,
  Trash2,
  LogOut,
  Check,
  ChevronRight,
  PanelLeft,
  LoaderCircle,
  X,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import "./style.css";
const saved = () => {
  try {
    return JSON.parse(sessionStorage.getItem("docmind") || "null");
  } catch {
    return null;
  }
};
function App() {
  const [session, setSession] = useState(saved),
    [view, setView] = useState("chat"),
    [docs, setDocs] = useState([]),
    [chats, setChats] = useState([]),
    [messages, setMessages] = useState([]),
    [chatId, setChatId] = useState(null),
    [selected, setSelected] = useState([]),
    [question, setQuestion] = useState(""),
    [mode, setMode] = useState("local"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [results, setResults] = useState(null),
    [mobile, setMobile] = useState(false),
    [citation, setCitation] = useState(null),
    [register, setRegister] = useState(false),
    [authBusy, setAuthBusy] = useState(false);
  const input = useRef(),
    end = useRef();
  async function api(path, options = {}) {
    const h = { ...options.headers };
    if (session) h.Authorization = "Bearer " + session.token;
    if (options.body && !(options.body instanceof FormData))
      h["Content-Type"] = "application/json";
    const res = await fetch("/api" + path, { ...options, headers: h });
    if (!res.ok) {
      let data;
      try {
        data = await res.json();
      } catch {}
      if (res.status === 401 && session) {
        sessionStorage.removeItem("docmind");
        setSession(null);
      }
      throw Error(data?.message || "Request failed. Please try again.");
    }
    return res.status === 204 || res.headers.get("content-length") === "0"
      ? null
      : res.text().then((t) => (t ? JSON.parse(t) : null));
  }
  async function refresh() {
    const [d, c] = await Promise.all([
      api("/documents"),
      api("/conversations"),
    ]);
    setDocs(d);
    setChats(c);
    setSelected((s) => s.filter((id) => d.some((x) => x.id === id)));
  }
  useEffect(() => {
    fetch("/api/health")
      .then((r) => r.json())
      .then((h) => setMode(h.mode))
      .catch(() => {});
    if (session)
      api("/auth/me")
        .then(refresh)
        .catch((e) => setError(e.message));
  }, [session]);
  useEffect(() => {
    end.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy]);
  async function signIn(e) {
    e.preventDefault();
    setAuthBusy(true);
    setError("");
    const data = Object.fromEntries(new FormData(e.target));
    try {
      const s = await api(register ? "/auth/register" : "/auth/login", {
        method: "POST",
        body: JSON.stringify(data),
      });
      sessionStorage.setItem("docmind", JSON.stringify(s));
      setSession(s);
    } catch (e) {
      setError(e.message);
    } finally {
      setAuthBusy(false);
    }
  }
  function newChat() {
    if (busy) return;
    setChatId(null);
    setMessages([]);
    setView("chat");
    setQuestion("");
    setMobile(false);
    setError("");
  }
  async function openChat(id) {
    if (busy) return;
    setError("");
    try {
      const m = await api("/conversations/" + id);
      setChatId(id);
      setMessages(m);
      setView("chat");
      setMobile(false);
    } catch (e) {
      setError(e.message);
    }
  }
  async function ask(e) {
    e?.preventDefault();
    if (!question.trim() || busy || !docs.length) return;
    setBusy(true);
    setError("");
    const q = question.trim();
    setQuestion("");
    try {
      const result = await api("/chat", {
        method: "POST",
        body: JSON.stringify({
          question: q,
          conversationId: chatId,
          documentIds: selected,
        }),
      });
      setChatId(result.conversationId);
      setMessages(result.messages);
      await refresh();
    } catch (e) {
      setError(e.message);
      setQuestion(q);
    } finally {
      setBusy(false);
    }
  }
  async function upload(files) {
    if (busy) return;
    setBusy(true);
    setError("");
    let count = 0;
    try {
      for (const file of files) {
        const form = new FormData();
        form.append("file", file);
        await api("/documents", { method: "POST", body: form });
        count++;
      }
      await refresh();
      setNotice(
        `${count} document${count === 1 ? "" : "s"} indexed. Ready to explore.`,
      );
    } catch (e) {
      setError(e.message);
      await refresh().catch(() => {});
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }
  async function remove(id) {
    if (!confirm("Delete this document and its indexed passages?")) return;
    try {
      await api("/documents/" + id, { method: "DELETE" });
      await refresh();
    } catch (e) {
      setError(e.message);
    }
  }
  async function search(e) {
    e.preventDefault();
    if (!question.trim()) return;
    setBusy(true);
    setError("");
    try {
      setResults(await api("/search?q=" + encodeURIComponent(question)));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function logout() {
    try {
      await api("/auth/logout", { method: "POST" });
    } catch {}
    sessionStorage.removeItem("docmind");
    setSession(null);
    setDocs([]);
    setChats([]);
    setMessages([]);
    setChatId(null);
    setError("");
  }
  if (!session)
    return (
      <div className="auth-shell">
        <section className="auth-story">
          <a className="brand">
            <BookOpen /> DocMind<span> / </span>
          </a>
          <div>
            <span className="eyebrow">LESS SEARCHING. MORE UNDERSTANDING.</span>
            <h1>
              Your knowledge.
              <br />A clearer conversation.
            </h1>
            <p>
              Turn dense documents into useful answers. Explore ideas, connect
              the details, and trace every answer to its source.
            </p>
            <div className="story-card">
              <FileText />
              <div>
                <strong>From pages to perspective</strong>
                <p>Upload. Ask. Understand.</p>
              </div>
              <Sparkles />
            </div>
          </div>
          <small>Built for curious minds · Powered by your documents</small>
        </section>
        <section className="auth-form">
          <div className="auth-box">
            <span className="eyebrow">YOUR PERSONAL KNOWLEDGE SPACE</span>
            <h2>
              {register ? "Make room for better thinking." : "Welcome back."}
            </h2>
            <p>
              {register
                ? "Create your DocMind account to get started."
                : "Sign in and pick up where you left off."}
            </p>
            <form onSubmit={signIn}>
              {register && (
                <label>
                  Your name
                  <input
                    name="name"
                    maxLength="80"
                    required
                    autoComplete="name"
                    placeholder="Kartikeya"
                  />
                </label>
              )}
              <label>
                Email address
                <input
                  name="email"
                  type="email"
                  maxLength="254"
                  required
                  autoComplete="email"
                  placeholder="you@example.com"
                />
              </label>
              <label>
                Password
                <input
                  name="password"
                  type="password"
                  minLength={register ? 10 : 1}
                  maxLength="72"
                  required
                  autoComplete={register ? "new-password" : "current-password"}
                  placeholder={
                    register ? "At least 10 characters" : "Enter your password"
                  }
                />
              </label>
              {error && (
                <div className="error" role="alert">
                  {error}
                </div>
              )}
              <button className="primary" disabled={authBusy}>
                {authBusy ? (
                  <LoaderCircle className="spin" />
                ) : register ? (
                  "Create account"
                ) : (
                  "Sign in"
                )}
                <ChevronRight size={18} />
              </button>
            </form>
            <p className="switch">
              {register ? "Already have an account?" : "New to DocMind?"}{" "}
              <button
                onClick={() => {
                  setRegister(!register);
                  setError("");
                }}
              >
                {register ? "Sign in" : "Create an account"}
              </button>
            </p>
            <div className="privacy">
              <ShieldCheck size={18} /> Your documents belong to your account.
            </div>
          </div>
        </section>
      </div>
    );
  const title =
    view === "library"
      ? "Document library"
      : view === "search"
        ? "Find the right passage"
        : chatId
          ? chats.find((c) => c.id === chatId)?.title || "Conversation"
          : "Your documents, understood";
  return (
    <div className="app">
      <aside className={mobile ? "sidebar open" : "sidebar"}>
        <a className="brand">
          <BookOpen /> DocMind<span> / </span>
        </a>
        <button className="new-chat" onClick={newChat} disabled={busy}>
          <Plus size={18} />
          New conversation
        </button>
        <span className="nav-label">WORKSPACE</span>
        <nav>
          <button
            className={view === "chat" ? "active" : ""}
            onClick={() => {
              setView("chat");
              setMobile(false);
            }}
          >
            <MessageSquare size={18} />
            Ask your documents
          </button>
          <button
            className={view === "library" ? "active" : ""}
            onClick={() => {
              setView("library");
              setMobile(false);
            }}
          >
            <FileText size={18} />
            Document library <em>{docs.length}</em>
          </button>
          <button
            className={view === "search" ? "active" : ""}
            onClick={() => {
              setView("search");
              setQuestion("");
              setMobile(false);
            }}
          >
            <Search size={18} />
            Search passages
          </button>
        </nav>
        <div className="recent">
          <span className="nav-label">RECENT CONVERSATIONS</span>
          {chats.length === 0 ? (
            <p className="muted">Your next idea starts here.</p>
          ) : (
            chats.map((c) => (
              <div className="chat-row" key={c.id}>
                <button
                  disabled={busy}
                  onClick={() => openChat(c.id)}
                  className={c.id === chatId ? "chosen" : ""}
                >
                  <MessageSquare size={14} />
                  <span>{c.title}</span>
                </button>
                <button
                  aria-label="Delete conversation"
                  disabled={busy}
                  onClick={async () => {
                    if (!confirm("Delete this conversation?")) return;
                    try {
                      await api("/conversations/" + c.id, { method: "DELETE" });
                      if (chatId === c.id) newChat();
                      await refresh();
                    } catch (e) {
                      setError(e.message);
                    }
                  }}
                >
                  <Trash2 size={13} />
                </button>
              </div>
            ))
          )}
        </div>
        <div className="sidebar-bottom">
          <div className="mode-card">
            <span className="dot" />
            <div>
              <strong>
                {mode === "ai" ? "AI connected" : "Local workspace"}
              </strong>
              <small>
                {mode === "ai"
                  ? "Semantic search & grounded answers"
                  : "Source excerpts · No API key needed"}
              </small>
            </div>
          </div>
          <div className="profile">
            <div className="avatar">{session.user.name[0]?.toUpperCase()}</div>
            <div>
              <strong>{session.user.name}</strong>
              <small>Personal workspace</small>
            </div>
            <button aria-label="Sign out" onClick={logout}>
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>
      <main>
        <header>
          <div>
            <button
              className="mobile-toggle"
              aria-label="Toggle navigation"
              onClick={() => setMobile(!mobile)}
            >
              <PanelLeft />
            </button>
            <span className="breadcrumb">
              Workspace <ChevronRight size={12} />{" "}
              {view === "chat"
                ? "Conversations"
                : view === "library"
                  ? "Library"
                  : "Search"}
            </span>
          </div>
          <span className="private">
            <ShieldCheck size={14} /> Private workspace
          </span>
        </header>
        {error && (
          <div className="banner error" role="alert">
            {error}
            <button aria-label="Dismiss error" onClick={() => setError("")}>
              <X size={16} />
            </button>
          </div>
        )}
        {notice && (
          <div className="banner success" role="status">
            {notice}
            <button
              aria-label="Dismiss notification"
              onClick={() => setNotice("")}
            >
              <X size={16} />
            </button>
          </div>
        )}
        <input
          ref={input}
          type="file"
          accept=".pdf,.txt,.md"
          multiple
          hidden
          onChange={(e) => upload([...e.target.files])}
        />
        {view === "library" ? (
          <section className="page">
            <div className="page-heading">
              <div>
                <span className="eyebrow">YOUR KNOWLEDGE, ORGANIZED</span>
                <h1>{title}</h1>
                <p>A home for the sources behind your next insight.</p>
              </div>
              <button
                className="primary"
                disabled={busy}
                onClick={() => input.current.click()}
              >
                {busy ? (
                  <LoaderCircle className="spin" size={17} />
                ) : (
                  <Plus size={17} />
                )}
                Upload documents
              </button>
            </div>
            <div
              className="dropzone"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                upload([...e.dataTransfer.files]);
              }}
            >
              <div className="file-icon">
                <Upload />
              </div>
              <strong>Drop a little knowledge here</strong>
              <p>PDF, TXT, or Markdown · Up to 15 MB per file</p>
              <button disabled={busy} onClick={() => input.current.click()}>
                or browse files <ChevronRight size={14} />
              </button>
            </div>
            <div className="section-title">
              <h3>All documents</h3>
              <span>{docs.length} sources</span>
            </div>
            {!docs.length ? (
              <div className="empty-list">
                Your library is waiting for its first document.
              </div>
            ) : (
              <div className="document-list">
                {docs.map((d) => (
                  <article key={d.id}>
                    <div className="file-icon">
                      <FileText size={22} />
                    </div>
                    <div className="doc-info">
                      <strong>{d.name}</strong>
                      <small>
                        {d.pages} page{d.pages === 1 ? "" : "s"} · {d.chunks}{" "}
                        passages · {(d.size_bytes / 1024).toFixed(0)} KB
                      </small>
                    </div>
                    <span className="ready">
                      <Check size={13} />
                      Ready
                    </span>
                    <time>{new Date(d.created_at).toLocaleDateString()}</time>
                    <button
                      className="icon-btn"
                      aria-label={"Delete " + d.name}
                      disabled={busy}
                      onClick={() => remove(d.id)}
                    >
                      <Trash2 size={17} />
                    </button>
                  </article>
                ))}
              </div>
            )}
          </section>
        ) : view === "search" ? (
          <section className="page">
            <span className="eyebrow">FOLLOW THE THREAD</span>
            <h1>{title}</h1>
            <p>Search across your library and go straight to the source.</p>
            <form className="search-box" onSubmit={search}>
              <Search size={20} />
              <input
                aria-label="Search query"
                value={question}
                maxLength={2000}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder="Search a topic, phrase, or idea…"
              />
              <button className="primary" disabled={busy || !question.trim()}>
                {busy ? "Searching…" : "Search"}
              </button>
            </form>
            {results !== null && (
              <>
                <div className="section-title">
                  <h3>Matching passages</h3>
                  <span>{results.length} results</span>
                </div>
                {results.length === 0 ? (
                  <div className="empty-list">
                    No supporting passages found. Try another search.
                  </div>
                ) : (
                  results.map((r) => (
                    <article className="search-result" key={r.id}>
                      <div>
                        <FileText size={16} />
                        <strong>{r.name}</strong>
                        <span>Page {r.page}</span>
                      </div>
                      <p>{r.content}</p>
                    </article>
                  ))
                )}
              </>
            )}
          </section>
        ) : (
          <section className="conversation">
            <div className="chat-heading">
              <span className="eyebrow">ASK. EXPLORE. CONNECT.</span>
              <h1>{title}</h1>
              {!messages.length && (
                <p>Good questions deserve answers you can trace.</p>
              )}
            </div>
            {messages.length === 0 ? (
              <div className="welcome">
                <div className="welcome-symbol">
                  <BookOpen size={36} />
                  <span>
                    <Sparkles size={16} />
                  </span>
                </div>
                <h2>What would you like to understand?</h2>
                <p>
                  Ask a question across your library. We’ll bring the
                  <br className="desktop-break" /> relevant passages—and their
                  sources—into focus.
                </p>
                <div className="prompts">
                  {[
                    "What are the key findings?",
                    "What risks are mentioned?",
                    "What are the next steps?",
                  ].map((p, i) => (
                    <button key={p} onClick={() => setQuestion(p)}>
                      <span>0{i + 1}</span>
                      <strong>{p}</strong>
                      <ChevronRight size={17} />
                    </button>
                  ))}
                </div>
                {!docs.length && (
                  <button
                    className="upload-inline"
                    disabled={busy}
                    onClick={() => input.current.click()}
                  >
                    <Upload size={16} /> Upload your first document to begin
                  </button>
                )}
              </div>
            ) : (
              <div className="messages">
                {messages.map((m) => (
                  <article className={"message " + m.role} key={m.id}>
                    <div className="message-avatar">
                      {m.role === "user" ? (
                        session.user.name[0]
                      ) : (
                        <BookOpen size={18} />
                      )}
                    </div>
                    <div className="message-body">
                      <strong>
                        {m.role === "user" ? "You" : "DocMind"}
                        <span>
                          {m.role === "assistant"
                            ? m.mode === "local"
                              ? "Source excerpts"
                              : "Grounded answer"
                            : ""}
                        </span>
                      </strong>
                      <p>{m.content}</p>
                      {m.citations?.length > 0 && (
                        <div className="citations">
                          {m.citations.map((c) => (
                            <button
                              key={c.index}
                              onClick={() => setCitation(c)}
                            >
                              <span>{c.index}</span>
                              <FileText size={13} />
                              {c.name}
                              <small>p. {c.page}</small>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  </article>
                ))}
                <div ref={end} />
              </div>
            )}
            {busy && (
              <div className="thinking" role="status">
                <LoaderCircle size={17} className="spin" />{" "}
                {docs.length
                  ? "Reading your sources…"
                  : "Indexing your document…"}
              </div>
            )}
            <div className="composer-area">
              <div className="scope">
                <FileText size={14} />
                <span>Sources</span>
                <select
                  aria-label="Choose document scope"
                  value={selected[0] || ""}
                  onChange={(e) =>
                    setSelected(e.target.value ? [e.target.value] : [])
                  }
                >
                  <option value="">All documents ({docs.length})</option>
                  {docs.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}
                    </option>
                  ))}
                </select>
                <button disabled={busy} onClick={() => input.current.click()}>
                  <Plus size={14} />
                  Add source
                </button>
              </div>
              <form className="composer" onSubmit={ask}>
                <textarea
                  aria-label="Ask a question"
                  value={question}
                  maxLength={2000}
                  onChange={(e) => setQuestion(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.shiftKey) {
                      e.preventDefault();
                      ask();
                    }
                  }}
                  placeholder={
                    docs.length
                      ? "Ask something about your documents…"
                      : "Upload a document to start asking questions…"
                  }
                  rows={2}
                />
                <button
                  aria-label="Send question"
                  disabled={busy || !question.trim() || !docs.length}
                >
                  <ArrowUp size={20} />
                </button>
              </form>
              <small className="composer-note">
                {mode === "local"
                  ? "Local mode finds matching passages. AI mode adds semantic search and generated answers."
                  : "AI answers can make mistakes. Check the cited passages."}
              </small>
            </div>
          </section>
        )}
      </main>
      {citation && (
        <div className="modal-backdrop" onClick={() => setCitation(null)}>
          <section
            className="citation-modal"
            role="dialog"
            aria-modal="true"
            aria-label="Source passage"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              className="close-modal"
              aria-label="Close source"
              onClick={() => setCitation(null)}
            >
              <X />
            </button>
            <span className="eyebrow">SOURCE [{citation.index}]</span>
            <h2>{citation.name}</h2>
            <span className="ready">Page {citation.page}</span>
            <p>{citation.excerpt}</p>
            <small>
              This passage was retrieved when the answer was created.
            </small>
          </section>
        </div>
      )}
    </div>
  );
}
createRoot(document.getElementById("root")).render(<App />);
