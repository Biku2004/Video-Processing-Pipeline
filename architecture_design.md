# 🎬 MiniNetflix — Complete Architecture Design

A production-grade serverless video processing pipeline inspired by Netflix's architecture, built with **Spring Boot 3.2**, **React 18**, and **AWS managed services**.

---

## 1. High-Level System Architecture

```mermaid
graph TB
    subgraph Client["🖥️ Client Tier"]
        UI["React SPA<br/>(Vite + HLS.js)"]
    end

    subgraph API["⚙️ API Tier"]
        SB["Spring Boot 3.2 API<br/>Port 8080"]
        JWT["JWT Auth Filter"]
        RL["Rate Limiter<br/>(Bucket4j)"]
    end

    subgraph Data["💾 Data Tier"]
        PG["PostgreSQL<br/>(Supabase)"]
        RD["Redis 7<br/>(Docker)"]
    end

    subgraph AWS["☁️ AWS Cloud Services"]
        S3I["S3 Input Bucket<br/>(raw uploads)"]
        SQS["SQS FIFO Queue"]
        DLQ["Dead Letter Queue"]
        MC["AWS MediaConvert<br/>(H.264, HLS)"]
        S3O["S3 Output Bucket<br/>(processed HLS)"]
        CF["CloudFront CDN<br/>(Signed URLs)"]
        CW["CloudWatch<br/>(Alarms + Events)"]
    end

    UI -->|"REST + JWT"| SB
    SB --> JWT
    SB --> RL
    SB -->|"JPA/Hibernate"| PG
    RL -->|"Token Bucket State"| RD
    SB -->|"Presigned PUT URL"| S3I
    UI -->|"Direct Upload (XHR PUT)"| S3I
    SB -->|"Queue Job"| SQS
    SQS -->|"Failed 3x"| DLQ
    SQS --> MC
    MC -->|"HLS Output"| S3O
    S3O --> CF
    CF -->|"RSA-Signed HLS Stream"| UI
    CW -->|"Job Status Webhook"| SB
    CW -->|"DLQ Depth Alarm"| DLQ
```

---

## 2. Technology Stack

### Backend
| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| Framework | Spring Boot | 3.2.0 | REST API, DI, auto-config |
| Language | Java | 17 | LTS, records, sealed classes |
| ORM | Spring Data JPA / Hibernate | — | Entity mapping, repositories |
| Security | Spring Security + JJWT | 0.12.3 | JWT auth, BCrypt, filter chain |
| Rate Limiting | Bucket4j + Redis | 8.7.0 | Token bucket, distributed state |
| AWS SDK | AWS SDK v2 | 2.25.27 | S3, SQS, MediaConvert, CloudFront |
| Database | PostgreSQL (Supabase) | 15 | Persistent storage |
| Cache | Redis | 7 (Alpine) | Rate limit state, session cache |
| Monitoring | Spring Actuator + Prometheus | — | Health checks, metrics |
| Build | Maven | 3.9+ | Dependency management |

### Frontend
| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| Framework | React | 18.2 | Component-based UI |
| Build Tool | Vite | 5.0 | Fast HMR dev server, bundler |
| Routing | React Router DOM | 6.20 | SPA client-side routing |
| HTTP Client | Axios | 1.6 | API calls with interceptors |
| Video Player | HLS.js | 1.4.14 | Adaptive bitrate streaming |
| File Upload | react-dropzone | 14.2 | Drag & drop file selection |
| Notifications | react-hot-toast | 2.4 | Toast notifications |
| Icons | lucide-react | 0.294 | SVG icon components |

### Infrastructure
| Service | Purpose | Config |
|---------|---------|--------|
| S3 Input Bucket | Raw video uploads | 7-day lifecycle expiry |
| S3 Output Bucket | Processed HLS output | 30-day → Glacier transition |
| SQS FIFO Queue | Decouple upload from processing | Content-based dedup, 5-min visibility |
| SQS DLQ | Capture failed jobs | 14-day retention, max 3 retries |
| AWS MediaConvert | Professional transcoding | H.264, multi-resolution HLS |
| CloudFront | Global CDN delivery | Signed URLs, cache-optimized |
| CloudWatch | Observability | DLQ depth alarms, job events |
| IAM | Least privilege access | Scoped MediaConvert role |

---

## 3. Backend Architecture (Layered)

```mermaid
graph LR
    subgraph Controllers
        AC["AuthController<br/>/api/auth/*"]
        VC["VideoController<br/>/api/videos/*"]
    end

    subgraph Services
        AS["AuthService"]
        VS["VideoService"]
        S3["S3Service"]
        SQS["SqsService"]
        MCS["MediaConvertService"]
    end

    subgraph Security
        JF["JwtAuthFilter"]
        JU["JwtUtil"]
        SC["SecurityConfig"]
    end

    subgraph CrossCutting
        RLS["RateLimitService"]
        GEH["GlobalExceptionHandler"]
    end

    subgraph Data
        UR["UserRepository"]
        VR["VideoRepository"]
        UM["User Entity"]
        VM["Video Entity"]
    end

    subgraph Config
        AW["AwsConfig"]
    end

    AC --> AS
    VC --> VS
    VC --> RLS
    VS --> S3
    VS --> SQS
    VS --> MCS
    VS --> VR
    AS --> UR
    AS --> JU
    JF --> JU
    AW --> S3
    AW --> SQS
    AW --> MCS
```

### Package Structure

```
com.mininetflix/
├── VideoStreamingApplication.java    # @SpringBootApplication entry point
├── config/
│   ├── AwsConfig.java                # AWS client beans (S3, SQS, MediaConvert, Presigner)
│   └── SecurityConfig.java           # Security filter chain, CORS, BCrypt, AuthManager
├── controller/
│   ├── AuthController.java           # POST /register, /login
│   └── VideoController.java          # CRUD videos, upload-url, confirm, stream, webhook
├── dto/
│   ├── AuthDto.java                  # RegisterRequest, LoginRequest, AuthResponse
│   ├── VideoDto.java                 # UploadUrlRequest/Response, VideoResponse, ConfirmRequest
│   └── RateLimitDto.java             # Rate limit status DTO
├── exception/
│   └── GlobalExceptionHandler.java   # @RestControllerAdvice (validation, rate-limit, general)
├── model/
│   ├── User.java                     # JPA entity + UserDetails (UUID, email, tier, BCrypt pwd)
│   └── Video.java                    # JPA entity (UUID, status FSM, S3 keys, job tracking)
├── ratelimit/
│   └── RateLimitService.java         # Tier-based upload + file size limits
├── repository/
│   ├── UserRepository.java           # findByEmail, findByUsername, existsBy*
│   └── VideoRepository.java          # countTodayUploads, findByStatus, findByJobId
├── security/
│   ├── JwtAuthFilter.java            # OncePerRequestFilter — extracts/validates Bearer token
│   └── JwtUtil.java                  # HMAC-SHA256 JWT generation, validation, claims extraction
└── service/
    ├── AuthService.java              # Register (BCrypt + JWT), Login (AuthManager + JWT)
    ├── VideoService.java             # Core orchestrator — upload, confirm, stream, status, poll
    ├── S3Service.java                # Presigned URLs, CloudFront signed URLs, object management
    ├── SqsService.java               # Queue video processing jobs to FIFO queue
    └── MediaConvertService.java      # Create transcoding jobs, adaptive bitrate output groups
```

---

## 4. Data Models

### User Entity

```mermaid
erDiagram
    USERS {
        string id PK "UUID"
        string email UK "unique, not null"
        string username UK "unique, not null"
        string password "BCrypt hash"
        enum tier "FREE | PRO | ADMIN"
        int upload_count_today
        datetime upload_reset_date
        datetime created_at
    }
```

### Video Entity (with State Machine)

```mermaid
erDiagram
    VIDEOS {
        string id PK "UUID"
        string title "not null"
        string original_filename
        string user_id FK "→ users.id"
        enum status "UPLOADED | QUEUED | PROCESSING | READY | FAILED | DELETED"
        string input_s3_key
        string output_s3_prefix
        string master_playlist_url
        string thumbnail_url
        string mediaconvert_job_id "idempotency key"
        long file_size_bytes
        long duration_seconds
        string original_resolution
        boolean has_1080p
        boolean has_720p
        boolean has_480p
        string error_message
        int retry_count
        datetime created_at
        datetime updated_at
        datetime processing_started_at
        datetime completed_at
    }

    USERS ||--o{ VIDEOS : "uploads"
```

### Video Status State Machine

```mermaid
stateDiagram-v2
    [*] --> UPLOADED: User uploads to S3
    UPLOADED --> QUEUED: confirmUpload() → SQS
    QUEUED --> PROCESSING: triggerMediaConvert()
    PROCESSING --> READY: Webhook status=COMPLETE
    PROCESSING --> FAILED: Webhook status=ERROR
    FAILED --> PROCESSING: Retry (max 3x)
    FAILED --> DELETED: Soft delete
    READY --> DELETED: Soft delete
```

---

## 5. Core Data Flows

### 5.1 — User Registration / Login

```mermaid
sequenceDiagram
    participant C as React Client
    participant A as AuthController
    participant S as AuthService
    participant DB as PostgreSQL
    participant J as JwtUtil

    C->>A: POST /api/auth/register {username, email, password}
    A->>S: register(request)
    S->>DB: existsByEmail? existsByUsername?
    S->>DB: save(User) — BCrypt password
    S->>J: generateToken(user)
    J-->>S: JWT (HMAC-SHA256, 24h expiry)
    S-->>A: AuthResponse {token, userId, username, email, tier}
    A-->>C: 201 Created
    C->>C: Store token + user in localStorage
```

### 5.2 — Video Upload Pipeline (Core Flow)

```mermaid
sequenceDiagram
    participant C as React Client
    participant V as VideoController
    participant VS as VideoService
    participant RL as RateLimitService
    participant S3 as S3Service
    participant SQS as SqsService
    participant MC as MediaConvertService
    participant DB as PostgreSQL
    participant S3Bucket as S3 Input Bucket

    Note over C,S3Bucket: Step 1 — Get Presigned Upload URL
    C->>V: POST /api/videos/upload-url {filename, contentType, fileSize, title}
    V->>RL: checkUploadLimit(user)
    V->>RL: checkFileSizeLimit(user, fileSize)
    V->>VS: generateUploadUrl(user, ...)
    VS->>DB: save(new Video) status=UPLOADED
    VS->>S3: generatePresignedUploadUrl(s3Key)
    S3-->>VS: Presigned PUT URL (5-min expiry)
    VS-->>C: {videoId, uploadUrl, s3Key, expiresInSeconds}

    Note over C,S3Bucket: Step 2 — Direct Upload to S3
    C->>S3Bucket: XHR PUT (binary video file, progress tracking)
    S3Bucket-->>C: 200 OK

    Note over C,S3Bucket: Step 3 — Confirm & Trigger Processing
    C->>V: POST /api/videos/{videoId}/confirm
    V->>VS: confirmUpload(user, videoId)
    VS->>DB: update status → QUEUED
    VS->>SQS: sendVideoProcessingJob(videoId, s3Key)
    VS->>VS: triggerMediaConvert(video)
    VS->>MC: createTranscodingJob(videoId, s3Key, resolution)
    MC-->>VS: jobId
    VS->>DB: save jobId, status → PROCESSING
    VS-->>C: VideoDto (status=PROCESSING)
```

### 5.3 — Video Processing & Completion

```mermaid
sequenceDiagram
    participant MC as AWS MediaConvert
    participant S3 as S3 Output Bucket
    participant CW as CloudWatch Events
    participant V as VideoController
    participant VS as VideoService
    participant DB as PostgreSQL

    MC->>S3: Write HLS segments + playlists (1080p, 720p, 480p)
    MC->>CW: Job status event (COMPLETE/ERROR)
    CW->>V: POST /api/videos/webhook/mediaconvert {jobId, status}
    V->>VS: updateVideoStatus(jobId, "COMPLETE")
    VS->>DB: findByMediaConvertJobId(jobId)
    VS->>DB: status → READY, set completedAt

    Note over VS,DB: Fallback: pollProcessingJobs() every 2 min
    VS->>DB: Find stuck PROCESSING/QUEUED videos > 10 min
    VS->>MC: getJobStatus(jobId) — poll fallback
```

### 5.4 — Video Streaming (Signed URL Flow)

```mermaid
sequenceDiagram
    participant C as React Client
    participant HLS as HLS.js Player
    participant V as VideoController
    participant VS as VideoService
    participant S3 as S3Service
    participant CF as CloudFront CDN
    participant S3O as S3 Output Bucket

    C->>V: GET /api/videos/{videoId}/stream
    V->>VS: getStreamingInfo(user, videoId)
    VS->>S3: buildStreamingUrl(outputPrefix)
    S3->>S3O: List .m3u8 files → find master playlist
    S3-->>VS: CloudFront master playlist URL
    VS->>S3: signUrl(masterPlaylistUrl)
    Note over S3: RSA-2048 Custom Policy<br/>Resource: processed/{videoId}/*<br/>Expiry: 1 hour
    S3-->>VS: Signed URL (Policy + Signature + Key-Pair-Id)
    VS-->>C: {masterPlaylistUrl (signed), thumbnailUrl, has1080p, ...}

    C->>HLS: Initialize with signed master playlist URL
    HLS->>CF: GET master.m3u8 (signed)
    CF->>S3O: Fetch via OAC
    CF-->>HLS: Master playlist (lists resolution variants)
    HLS->>CF: GET 720p.m3u8 (signed params propagated)
    HLS->>CF: GET segment_001.ts (signed params propagated)
    Note over HLS: Custom SignedUrlLoader propagates<br/>query params to all segment requests
```

---

## 6. Security Architecture

```mermaid
graph TB
    subgraph Request["Incoming Request"]
        R["HTTP Request"]
    end

    subgraph FilterChain["Spring Security Filter Chain"]
        CORS["CORS Filter<br/>(localhost:3000, 5173)"]
        CSRF["CSRF Disabled<br/>(stateless API)"]
        JF["JwtAuthFilter<br/>(OncePerRequestFilter)"]
        AUTH["Authentication Provider<br/>(DaoAuthProvider + BCrypt)"]
    end

    subgraph Public["Public Endpoints"]
        P1["/api/auth/**"]
        P2["/actuator/health"]
        P3["/h2-console/**"]
    end

    subgraph Protected["Protected Endpoints"]
        PR1["/api/videos/**"]
    end

    R --> CORS --> CSRF --> JF
    JF -->|"No Bearer token"| Public
    JF -->|"Valid JWT"| AUTH --> Protected
    JF -->|"Invalid/Expired JWT"| REJECT["401 Unauthorized"]
```

### Security Measures

| Layer | Mechanism | Details |
|-------|-----------|---------|
| **Authentication** | JWT (HMAC-SHA256) | 24-hour expiry, username in subject claim |
| **Password Storage** | BCrypt | Salted hashing via Spring Security |
| **Upload Security** | Presigned URLs | 5-min expiry, scoped to specific S3 key |
| **Streaming Security** | CloudFront Signed URLs | RSA-2048, custom policy with wildcard resource, 1-hour expiry |
| **File Validation** | Content-type check | Only `video/*` MIME types accepted |
| **API Security** | Stateless sessions | No server-side sessions, pure JWT |
| **CORS** | Whitelist origins | Configurable via `CORS_ORIGINS` env var |
| **IAM** | Least privilege | MediaConvert role scoped to specific S3 buckets |
| **Docker** | Non-root user | Backend runs as non-root in container |

---

## 7. Rate Limiting Architecture

```mermaid
graph LR
    subgraph Tiers["User Tiers"]
        FREE["🆓 FREE Tier<br/>3 uploads/day<br/>500 MB max file"]
        PRO["⭐ PRO Tier<br/>50 uploads/day<br/>5 GB max file"]
    end

    subgraph Check["RateLimitService"]
        UL["checkUploadLimit()<br/>COUNT today's uploads"]
        FS["checkFileSizeLimit()<br/>Compare against tier max"]
    end

    subgraph Store["Storage"]
        DB["PostgreSQL<br/>(countTodayUploads query)"]
        RD["Redis<br/>(Bucket4j state)"]
    end

    FREE --> UL
    PRO --> UL
    UL --> DB
    FS --> DB
    UL -.->|"distributed state"| RD
```

---

## 8. Frontend Architecture

```mermaid
graph TB
    subgraph App["React SPA (Vite)"]
        BR["BrowserRouter"]
        AP["AuthProvider<br/>(Context API)"]
        NB["Navbar"]

        subgraph Routes["Routes"]
            PUB["Public Routes"]
            PRT["Protected Routes"]
        end

        subgraph Pages
            LP["LoginPage"]
            RP["RegisterPage"]
            DP["DashboardPage"]
            UP["UploadPage"]
            PP["PlayerPage"]
        end

        subgraph Components
            VC["VideoCard"]
        end

        subgraph API["API Layer"]
            CL["Axios Client<br/>(baseURL: /api)"]
            S3U["uploadToS3()<br/>(XHR direct PUT)"]
        end
    end

    BR --> AP
    AP --> NB
    AP --> Routes
    PUB --> LP & RP
    PRT --> DP & UP & PP
    DP --> VC
    LP & RP --> CL
    UP --> CL & S3U
    PP --> CL
```

### Frontend Route Map

| Route | Component | Guard | Purpose |
|-------|-----------|-------|---------|
| `/` | — | Redirect | → `/dashboard` or `/login` |
| `/login` | `LoginPage` | Public | Email + password login |
| `/register` | `RegisterPage` | Public | Username + email + password |
| `/dashboard` | `DashboardPage` | Protected | Video grid, rate limit status, refresh |
| `/upload` | `UploadPage` | Protected | Drag & drop, 3-step progress, S3 direct upload |
| `/watch/:videoId` | `PlayerPage` | Protected | HLS.js adaptive player, signed URL loader |

### Key Frontend Patterns

- **Auth Context** — `AuthProvider` wraps entire app, stores JWT in `localStorage`, auto-restores session
- **Axios Interceptors** — Auto-attach `Bearer` token on every request; auto-redirect to `/login` on 401
- **Direct S3 Upload** — `uploadToS3()` uses `XMLHttpRequest` (not Axios) for progress tracking via `xhr.upload.progress`
- **Signed URL Propagation** — Custom `SignedUrlLoader` for HLS.js that propagates CloudFront query params (`Policy`, `Signature`, `Key-Pair-Id`) from the master playlist URL to all segment requests

---

## 9. Infrastructure as Code (CloudFormation)

```mermaid
graph TB
    subgraph CF["CloudFormation Stack: mininetflix"]
        IB["S3 Input Bucket<br/>• CORS for PUT/POST<br/>• 7-day lifecycle delete"]
        OB["S3 Output Bucket<br/>• 30-day → Glacier<br/>• OAC policy for CloudFront"]
        Q["SQS FIFO Queue<br/>• Content-based dedup<br/>• 5-min visibility timeout"]
        D["SQS DLQ (FIFO)<br/>• 14-day retention<br/>• maxReceiveCount: 3"]
        R["IAM MediaConvert Role<br/>• s3:GetObject on input<br/>• s3:PutObject on output"]
        CD["CloudFront Distribution<br/>• S3 origin<br/>• Cache-optimized<br/>• .m3u8 no-cache behavior"]
        AL["CloudWatch Alarm<br/>• DLQ depth > 0<br/>• 5-min evaluation"]
    end

    Q -->|"Failed 3x"| D
    R --> IB
    R --> OB
    CD --> OB
    AL --> D
```

---

## 10. MediaConvert Encoding Strategy

| Output | Resolution | Bitrate | When Generated |
|--------|-----------|---------|----------------|
| 1080p | 1920×1080 | 5 Mbps | Input ≥ 1080p |
| 720p | 1280×720 | 2.5 Mbps | Input ≥ 720p |
| 480p | 854×480 | 1 Mbps | Always |

- **HLS segmentation**: 6-second chunks for fast startup
- **Audio**: AAC-LC, 96 kbps, 48 kHz sample rate
- **Smart encoding**: Only generates resolutions ≤ input resolution (cost optimization)
- **Output structure**: `processed/{videoId}/` — master playlist + per-resolution playlists + `.ts` segments

---

## 11. Fault Tolerance & Reliability

| Mechanism | Implementation | Purpose |
|-----------|---------------|---------|
| **SQS DLQ** | Failed jobs after 3 retries → DLQ | Prevent data loss |
| **Idempotent Job Creation** | Check `mediaConvertJobId` before creating | Prevent duplicate encoding |
| **Polling Fallback** | `@Scheduled` every 2 min for stuck jobs | Handle missed webhooks |
| **Soft Deletes** | `VideoStatus.DELETED` (never hard delete) | Data recovery |
| **Structured Error Handling** | `GlobalExceptionHandler` with typed responses | Consistent API errors |
| **CloudWatch Alarm** | DLQ depth ≥ 1 triggers alarm | Ops alerting |
| **Retry Tracking** | `retryCount` + `errorMessage` on `Video` entity | Debug failed jobs |

---

## 12. Observability

| Tool | Endpoint / Config | Metrics |
|------|-------------------|---------|
| Spring Actuator | `/actuator/health` | App health, DB, Redis |
| Prometheus | `/actuator/prometheus` | JVM, HTTP, custom metrics |
| Structured Logging | SLF4J + Logback | `com.mininetflix: DEBUG` |
| CloudWatch | Alarm on DLQ depth | Encoding failure detection |
| MediaConvert | Job progress API | Transcoding % complete |

---

## 13. Deployment Architecture

```mermaid
graph TB
    subgraph Docker["Docker Compose (Local Dev)"]
        RD["Redis 7-Alpine<br/>Port 6379<br/>256MB max-memory<br/>allkeys-lru eviction"]
    end

    subgraph Local["Local Development"]
        BE["Spring Boot<br/>Port 8080<br/>(mvn spring-boot:run)"]
        FE["Vite Dev Server<br/>Port 5173 → proxy /api → 8080"]
    end

    subgraph Production["Production (Docker)"]
        BED["Backend Dockerfile<br/>• Maven build stage<br/>• Eclipse Temurin 17 JRE<br/>• Non-root user"]
        FED["Frontend Dockerfile<br/>• Node build stage<br/>• Nginx static serve<br/>• Custom nginx.conf"]
    end

    subgraph External["External Services"]
        SB["Supabase<br/>(PostgreSQL)"]
        AWS["AWS<br/>(S3, SQS, MediaConvert, CloudFront)"]
    end

    BE --> RD
    BE --> SB
    BE --> AWS
    FE --> BE
```

---

## 14. API Contract Summary

### Auth Endpoints (Public)

| Method | Endpoint | Request | Response |
|--------|----------|---------|----------|
| `POST` | `/api/auth/register` | `{username, email, password}` | `{token, userId, username, email, tier, message}` |
| `POST` | `/api/auth/login` | `{email, password}` | `{token, userId, username, email, tier, message}` |

### Video Endpoints (Authenticated)

| Method | Endpoint | Request | Response |
|--------|----------|---------|----------|
| `GET` | `/api/videos` | — | `[VideoDto]` |
| `GET` | `/api/videos/:id` | — | `VideoDto` |
| `POST` | `/api/videos/upload-url` | `{filename, contentType, fileSize, title}` | `{videoId, uploadUrl, s3Key, expiresInSeconds}` |
| `POST` | `/api/videos/:id/confirm` | `{fileSizeBytes?}` | `VideoDto` |
| `GET` | `/api/videos/:id/stream` | — | `{masterPlaylistUrl, thumbnailUrl, has1080p, ...}` |
| `DELETE` | `/api/videos/:id` | — | `204 No Content` |
| `GET` | `/api/videos/rate-limit` | — | `{uploadsToday, dailyLimit, remaining, tier}` |
| `POST` | `/api/videos/webhook/mediaconvert` | `{jobId, status, progress}` | `{received: true}` |

---

## 15. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Direct S3 upload** (presigned URLs) | Backend never handles video bytes → eliminates I/O bottleneck, scales horizontally |
| **SQS FIFO** between upload and processing | Decouples upload latency from encoding time; enables retry and ordering |
| **HLS over DASH** | Broader device support (iOS native), simpler CloudFront integration |
| **CloudFront Signed URLs** (custom policy) | Wildcard resource access (`processed/{videoId}/*`) lets one signature cover all segments |
| **PostgreSQL over DynamoDB** | Relational queries (countTodayUploads, complex joins) are natural in SQL |
| **Redis for rate limiting** | Distributed state for horizontal scaling; Bucket4j integration |
| **Soft deletes** | Never lose data; can audit and recover |
| **Polling fallback** for job status | CloudWatch webhooks can be missed; 2-min poll catches stuck jobs |
| **Smart encoding** (resolution-aware) | Don't waste money generating 1080p from a 480p source file |
| **6-second HLS segments** | Balance between startup latency and encoding overhead |
