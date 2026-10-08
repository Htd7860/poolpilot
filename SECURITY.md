# Security

The management API is disabled by default. The demo binds to loopback and includes a public local token. Replace it before exposing a deployment.

Use high-entropy named tokens with reader/operator roles and expiry, or integrate application authentication through a verified Principal or AdminAuthenticator. Named credentials are SHA-256 digests, not password hashes. Configure TLS, protect Actuator separately, and retain CSRF protection for cookie-authenticated writes.

Credential changes are loaded on application restart. Timeout observation does not terminate tasks. External sources are administrator-configured URLs; they must be trusted and use bounded I/O. Notifications and runtime alerts are not durable.

For vulnerability reports, use GitHub private vulnerability reporting when available, or contact the repository owner privately. Include the affected version, reproduction steps and impact. Do not include active credentials or production data in public issues.
