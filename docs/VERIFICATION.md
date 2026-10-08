# Verification

Verified on October 8, 2026 with Java 17.0.20, Spring Boot 3.5.16, Node 24 and Vite 6.4.4.

- Maven package: passed; 5 tests, 0 failures, 0 errors.
- Local integration: authentication, wrong password, duplicate email, owner-only library/search/delete, private chats, logout revocation, irrelevant-query fallback, cascading chunk deletion.
- PDF integration: generated a two-page PDF and verified search results retain page 2; rejected a fake PDF.
- AI integration: a local HTTP test provider returned deterministic embeddings and a cited answer. Verified provider failure returns 502 and does not save a partial document.
- React production build: passed.
- npm audit: 0 known vulnerabilities at verification time (including dev dependencies).
- Packaged JAR browser smoke: registration, document upload, question, citation dialog, saved history after reload, search, desktop and 390px mobile layouts; no JavaScript page errors or mobile horizontal overflow.
- File-backed H2 restart smoke: session, uploaded document and search result persisted across stop and restart.

Screenshots under `screenshots/` show the actual local-mode application.

## Not verified in this environment

Docker and PostgreSQL/pgvector execution were not run because Docker/PostgreSQL were not installed. Their configuration and SQL are included, but the executed integration tests used H2.
Live AI-provider credentials and real model output were not tested because no API key was supplied. The mocked-provider integration tests verify the HTTP boundary and pipeline, not real-model answer quality.
Windows startup scripts were inspected; the executed builds ran on Linux.
Public hosting and GitHub publishing were not performed.

## Reproduce

```sh
mvn -B -f backend/pom.xml verify
cd frontend
npm ci
npm run build
npm audit
```

Optional JAR persistence test (requires Python 3 and Java):

```sh
python scripts/persistence-smoke.py
```
