---
name: api-design-patterns
description: REST API design best practices including versioning, error handling, pagination, rate limiting, and security.
---

# API Design Patterns Skill

## URL Structure
- Use nouns, not verbs: `/users`, not `/getUsers`
- Use plural nouns: `/users/{id}`, not `/user/{id}`
- Use hyphens for multi-word paths: `/user-profiles`
- Version in the URL: `/api/v1/users`

## HTTP Methods
| Method | Purpose | Idempotent |
|--------|---------|-----------|
| GET | Read | Yes |
| POST | Create | No |
| PUT | Replace | Yes |
| PATCH | Update | Yes |
| DELETE | Remove | Yes |

## Error Handling
Always return consistent error objects:
```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "User with ID 123 not found",
    "status": 404,
    "details": {}
  }
}
```

## Pagination
Use cursor-based pagination for scalability:
```
GET /api/v1/users?cursor=abc123&limit=20
```
Response includes:
```json
{
  "data": [...],
  "pagination": {
    "nextCursor": "def456",
    "hasMore": true
  }
}
```

## Rate Limiting
- Return `429 Too Many Requests` with `Retry-After` header.
- Use `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` headers.

## Security
- Always validate input server-side (never trust the client).
- Use HTTPS exclusively.
- Implement CORS with explicit origin whitelist.
- Use API keys for public endpoints, OAuth2/JWT for user-scoped endpoints.
- Sign requests with HMAC for machine-to-machine communication.
