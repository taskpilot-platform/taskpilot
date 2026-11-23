# LiveKit Cloud Egress + Backblaze B2 + Google Drive 5TB Operational Setup

## 1. Backblaze B2 CORS Rules (Required for Tier 3 Fallback Browser Presigned PUT)

The Backblaze B2 Web Console does not expose full S3 `s3_put` CORS configuration. Use the official Backblaze B2 CLI (`pip install b2`):

```bash
b2 account authorize <EGRESS_S3_ACCESS_KEY> <EGRESS_S3_SECRET_KEY>

b2 bucket update --cors-rules '[
  {
    "corsRuleName": "taskpilot-presigned-recording-upload",
    "allowedOrigins": [
      "https://taskpilot-platform.netlify.app",
      "http://localhost:5173",
      "http://127.0.0.1:5173"
    ],
    "allowedOperations": [
      "s3_put",
      "s3_get",
      "s3_head"
    ],
    "allowedHeaders": [
      "content-type",
      "x-amz-*"
    ],
    "exposeHeaders": [
      "ETag",
      "Content-Length",
      "Content-Range",
      "Accept-Ranges"
    ],
    "maxAgeSeconds": 3600
  }
]' taskpilot-recordings-2026 allPrivate
```

## 2. Backblaze B2 Caps & Alerts + Lifecycle Policy

- **Caps & Alerts (Recommended):** In Backblaze Web Console → **Caps & Alerts**, configure a storage alert (e.g., at 7–8 GB) so you receive an email if Google Drive archiving stalls and files accumulate in B2.
- **Lifecycle Settings:** Set **Keep only the last version of the file** so that when the backend archive worker deletes a verified file from B2 after uploading to Google Drive 5TB, B2 does not retain hidden versions.
- **Do NOT configure a lifecycle rule that automatically deletes active files after N days**, because that would delete recordings that have not yet finished archiving to Google Drive.

## 3. Google Drive OAuth App (In Production)

Because Google Drive 5TB serves as cold archival storage (`EGRESS_ARCHIVE_GDRIVE_ENABLED=true`, `EGRESS_ARCHIVE_AFTER_DAYS=3`), ensure the Google Cloud OAuth Consent Screen is set to **In production** (not *Testing*), otherwise refresh tokens expire every 7 days.

All archived meeting recordings are uploaded as **private** files (never granted `anyone` link-sharing permission) and streamed via the authenticated backend HTTP Range proxy (`GET /api/v1/projects/{projectId}/files/{fileId}/stream`).
