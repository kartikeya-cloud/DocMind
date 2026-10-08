# DocMind

A Java + React document intelligence application with private document libraries, page-aware retrieval, source citations, and saved conversations.

## Quick start with the included runnable app

Install Java 17 or newer, extract this ZIP, and open PowerShell in the `docmind` folder:

```powershell
java -jar runtime/docmind.jar
```

Open **http://localhost:8080**, create an account, then upload `samples/product-brief.txt`.
This runs the full interface and backend with persistent local storage. No Node.js, Maven, Docker or API key is required for local mode.
You can enable AI mode with `OPENAI_API_KEY` as described below; for the included app, use the `runtime/docmind.jar` path in those commands.
The runtime JAR is a convenience build and is ignored by Git. Rebuild it from source for future changes.

## Start with Docker and PostgreSQL

Install Docker Desktop, extract this project, and open a terminal in its folder.

```sh
cp .env.example .env
# Windows PowerShell: Copy-Item .env.example .env
# Edit .env and choose a strong DB_PASSWORD.
docker compose up --build
```

Open **http://localhost:8080**, create an account, and upload `samples/product-brief.txt`.
Ask **“What is the release deadline?”** or **“What are the main risks?”**.
Your database persists in a Docker volume. Stop with `docker compose down`.
`docker compose down -v` permanently removes database data; use only for an intentional reset.

## Start without Docker

Requirements: Java 17+, Maven 3.6.3+, Node.js 22+.

For a single application build:

```sh
bash scripts/build.sh
java -jar backend/target/docmind-1.0.0.jar
```

Windows PowerShell:

```powershell
./scripts/build.ps1
java -jar backend/target/docmind-1.0.0.jar
```

Open http://localhost:8080. H2 saves data under `./data` relative to your launch directory.

For development, use two terminals:

```sh
mvn -f backend/pom.xml spring-boot:run
```

```sh
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173. Vite proxies API requests to the Java server.

## Enable AI mode

Set `OPENAI_API_KEY` on the **server** before startup. For Docker, place it in `.env`.
Never put provider keys in frontend code or commit `.env`.

```powershell
$env:OPENAI_API_KEY="your-key"
java -jar backend/target/docmind-1.0.0.jar
```

```sh
export OPENAI_API_KEY="your-key"
java -jar backend/target/docmind-1.0.0.jar
```

AI mode sends chunks to the embeddings provider and retrieved excerpts to the chat provider. It can incur charges. Local mode makes no provider calls and shows matching excerpts rather than generated prose.
The provider key must support the configured chat and embedding models.
When switching from local to AI mode, **delete and re-upload documents** so embeddings are created. Avoid mixing embedding models in one database: re-index all documents when changing the embedding model. The implementation expects 1536-dimensional embeddings.
The AI integration uses Spring RestClient and the provider's HTTP API, rather than Spring AI, to keep the provider boundary small and easy to test.

## Included functionality

- Registration, bcrypt password hashing, opaque expiring bearer sessions, logout.
- PDF, TXT, Markdown upload with size/page/chunk limits and transactional indexing.
- Private libraries and document deletion with cascading chunk cleanup.
- Page-aware chunks with overlap; keyword retrieval without an API key.
- Embedding retrieval in AI mode; pgvector cosine search and HNSW in PostgreSQL.
- Document-scoped questions and grounded answers with numbered source excerpts.
- Saved chats with account ownership checks and deletion.
- Responsive React UI, citation previews, progress and error states.
- Maven integration tests, CI workflow, Docker Compose and local build scripts.

## Test

```sh
mvn -B -f backend/pom.xml verify
cd frontend
npm ci
npm run build
```

See [architecture](docs/ARCHITECTURE.md), [API](docs/API.md), and [verification notes](docs/VERIFICATION.md).

## Limits and deployment

This is a complete runnable portfolio application, not a managed public deployment.
Scanned PDFs need OCR before upload; encrypted PDFs are rejected.
Uploads index synchronously. Embeddings currently use one provider request per chunk; large documents can take time. Provider failure leaves no partial indexed document.
Citation excerpts are saved snapshots and remain in historical chats after a document is deleted. Delete the conversation to remove those snapshots.
The UI stores its session token in sessionStorage (cleared when its tab session ends); server sessions expire after 24 hours.
For internet deployment, use HTTPS, a reverse proxy, per-account quotas, backups, and appropriate provider data policies. The included limiter is per process, not distributed. Configure proxy identity carefully before relying on IP-based auth limits. Password reset, email verification, OCR, billing, background ingestion, collaboration and refresh-token rotation are outside this version.

## Resume description

**DocMind — AI Document Intelligence Platform** | Java, Spring Boot, React, PostgreSQL, pgvector

Built a full-stack document Q&A platform with page-aware chunking, semantic retrieval, grounded AI answers, source citations, and private conversation history. Implemented secure account ownership checks, transactional indexing, and automated integration tests.

Only claim deployment, real users, latency or accuracy metrics after measuring them yourself.
