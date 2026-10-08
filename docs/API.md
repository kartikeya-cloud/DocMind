# API

All private endpoints require `Authorization: Bearer <token>`.
Responses are JSON. Error responses use `{ "message": "..." }`.

| Method | Path | Purpose |
|---|---|---|
| GET | /api/health | Health and active retrieval mode |
| POST | /api/auth/register | `{name,email,password}` → token and user |
| POST | /api/auth/login | `{email,password}` → token and user |
| GET | /api/auth/me | Current account |
| POST | /api/auth/logout | Revoke the current token |
| GET | /api/documents | Owner's document metadata |
| POST | /api/documents | Multipart `file` → indexed document |
| DELETE | /api/documents/{id} | Delete owner's document |
| GET | /api/search?q=... | Retrieve matching source passages |
| GET | /api/conversations | List owner's chats |
| GET | /api/conversations/{id} | Chat message history with citations |
| DELETE | /api/conversations/{id} | Delete chat and messages |
| POST | /api/chat | `{question,conversationId,documentIds}` → saved messages |

Use `conversationId: null` for a new chat and `documentIds: []` for all owned documents.
Each citation includes index, documentId, name, page, and excerpt.
Unauthenticated calls return 401. A foreign document/chat ID returns 404 on direct access. Invalid uploads return 400; oversize multipart requests return 413; provider errors return 502; rate limits return 429.
