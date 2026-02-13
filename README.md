# 🎬 MiniNetflix — Serverless Video Processing Pipeline

A production-grade video processing pipeline inspired by Netflix's architecture.
Built with **Spring Boot**, **React**, and **AWS** services.

---

## 🏗 Architecture

```
Client (React + HLS.js)
        ↓
   [Spring Boot API]
   ├── JWT Auth
   ├── Rate Limiting (Bucket4j + Redis)
   ├── Presigned URL Generator
   ├── CloudFront URL Signer (RSA)
   └── State Machine (VideoStatus)
        ↓
   [PostgreSQL (Supabase)]   ← persistent data storage
        ↓
   [Redis]                   ← distributed rate limiting
        ↓
   [S3 Input Bucket]         ← direct upload (no backend bottleneck)
        ↓
   [SQS FIFO Queue]          ← decouples upload from processing
   + [DLQ]                   ← dead letter queue for failed jobs
        ↓
   [AWS MediaConvert]        ← professional transcoding
   ├── H.264 encoding
   ├── Multi-resolution: 1080p / 720p / 480p
   └── HLS segmentation (6-second chunks)
        ↓
   [S3 Output Bucket]
        ↓
   [CloudFront CDN]          ← global low-latency delivery
   + [Signed URLs]           ← prevents unauthorized access & scraping
        ↓
   [React Player + HLS.js]   ← adaptive bitrate streaming
```

---

## 🚀 Quick Start (Local Dev)

### Prerequisites
- Java 17+, Maven 3.9+
- Node.js 20+
- Docker & Docker Compose
- AWS account with configured credentials
- PostgreSQL database (local or Supabase)
- Redis (local or Docker)

### Step 1 — Deploy AWS Infrastructure

```bash
cd infrastructure

# Deploy CloudFormation stack
aws cloudformation deploy \
  --template-file cloudformation.yml \
  --stack-name mininetflix \
  --capabilities CAPABILITY_NAMED_IAM \
  --region ap-south-1

# Get MediaConvert endpoint
aws mediaconvert describe-endpoints --region ap-south-1

# Generate RSA key pair for CloudFront Signed URLs
openssl genrsa -out private_key.pem 2048
openssl rsa -pubout -in private_key.pem -out public_key.pem

# Create CloudFront public key (via AWS Console or CLI)
# Then create a Key Group and attach it to your CloudFront distribution
# Note: Keep private_key.pem secure - never commit to git!
```

### Step 2 — Configure Environment

```bash
cp .env.example .env
# Fill in:
# - AWS credentials, bucket names, endpoints
# - CloudFront domain, key pair ID, and private key path
# - PostgreSQL/Supabase connection URL
# - Redis connection (if remote)
# - JWT secret (256-bit minimum)
```

### Step 3 — Run with Docker Compose

```bash
# Start Redis only (backend runs locally for development)
docker-compose up redis -d
```

Services:
- Frontend: http://localhost:3000 (if using Docker)
- Backend: http://localhost:8080
- Redis: localhost:6379
- PostgreSQL: Use Supabase or local instance

### Step 3 (Alternative) — Run Separately

**Backend:**
```bash
cd backend

# Production mode (PostgreSQL/Supabase + AWS + Redis):
export DB_URL=jdbc:postgresql://db.xxxxx.supabase.co:5432/postgres
export DB_USERNAME=postgres
export DB_PASSWORD=your_supabase_password
export JWT_SECRET=your-256-bit-secret-replace-this
export CLOUDFRONT_DOMAIN=https://xxxxx.cloudfront.net
export CLOUDFRONT_KEY_PAIR_ID=K1234567890ABC
export CLOUDFRONT_PRIVATE_KEY_PATH=./private_key.pem
export REDIS_HOST=localhost
export REDIS_PORT=6379
# ... other env vars from .env
mvn spring-boot:run
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev
# → http://localhost:3000
```

---

## 📡 API Reference

### Auth
| Method | Endpoint | Body | Response |
|--------|----------|------|----------|
| POST | `/api/auth/register` | `{username, email, password}` | `{token, userId, username, email, tier}` |
| POST | `/api/auth/login` | `{email, password}` | `{token, userId, username, email, tier}` |

### Videos
| Method | Endpoint | Body/Params | Response |
|--------|----------|-------------|----------|
| GET | `/api/videos` | — | `[VideoDto]` |
| GET | `/api/videos/:id` | — | `VideoDto` |
| POST | `/api/videos/upload-url` | `{filename, contentType, fileSize, title}` | `{videoId, uploadUrl, s3Key, expiresInSeconds}` |
| POST | `/api/videos/:id/confirm` | `{fileSizeBytes?}` | `VideoDto` |
| GET | `/api/videos/:id/stream` | — | `{masterPlaylistUrl, thumbnailUrl, ...}` |
| DELETE | `/api/videos/:id` | — | 204 |
| GET | `/api/videos/rate-limit` | — | `{uploadsToday, dailyLimit, remaining, tier}` |
| POST | `/api/videos/webhook/mediaconvert` | `{jobId, status, progress}` | `{received: true}` |

### VideoStatus State Machine
```
UPLOADED → QUEUED → PROCESSING → READY
                              ↘ FAILED (retryable)
```

---

## 🔥 Production Engineering Concepts Implemented

### Rate Limiting
- **Redis-powered distributed rate limiting** with Bucket4j
- Free tier: 3 uploads/day, 500MB max file
- Pro tier: 50 uploads/day, 5GB max file
- Token bucket algorithm for smooth traffic control
- Horizontal scaling support (shared state in Redis)

### Fault Tolerance
- SQS DLQ — failed jobs don't disappear
- Idempotency check before creating MediaConvert jobs
- Exponential retry logic (configurable)
- Soft deletes (videos never hard-deleted)
- Scheduled polling for stuck jobs (fallback to webhook)

### Cost Optimization
- Direct S3 upload (no backend I/O costs)
- Smart encoding: only generate resolutions ≤ input resolution
- S3 lifecycle rules: raw uploads deleted after 7 days
- Glacier transition for infrequently accessed output after 30 days
- ECS Fargate Spot option (commented in docker-compose)

### Security
- JWT auth with BCrypt password hashing
- Presigned URLs with 5-min expiry for uploads
- **CloudFront Signed URLs** for video streaming (prevents scraping)
  - RSA-2048 key pair for signing
  - Custom policy with wildcard resource access (e.g., `processed/{videoId}/*`)
  - 1-hour expiry on signed URLs
- File type validation (video/* only)
- IAM least privilege (MediaConvert role scoped to specific buckets)
- CORS configured for known origins only
- Non-root Docker user
- Database credentials stored in environment variables (never hardcoded)

### Observability
- Spring Actuator + Prometheus metrics exposed
- CloudWatch alarm on DLQ depth
- Structured logging with correlation IDs
- MediaConvert job progress tracking

### Adaptive Bitrate Streaming
- MediaConvert generates HLS playlists
- 6-second segments for fast startup
- Client auto-selects quality based on bandwidth
- HLS.js with ABR algorithm in React player

---

## 💰 Cost Estimate (Free Tier + Light Usage)

| Service | Cost |
|---------|------|
| S3 (10GB stored) | ~$0.23/month |
| MediaConvert (100 min/month) | ~$0.75/month |
| CloudFront (50GB egress) | ~$4.25/month |
| SQS (1M requests) | Free tier |
| Redis (self-hosted or free tier) | ~$0-15/month |
| PostgreSQL (Supabase free tier) | Free |
| **Total** | **~$5-20/month** |

---

## 🎯 Resume Bullet Point

> Designed and implemented a production-grade video processing pipeline using **Spring Boot**, **React**,
> **PostgreSQL**, **S3 presigned URLs**, **SQS**, and **AWS MediaConvert** with **HLS adaptive bitrate streaming**
> via **CloudFront Signed URLs**, featuring **JWT auth**, **Redis-based distributed rate limiting**,
> **idempotent job creation**, **DLQ fault tolerance**, and **cost-aware smart encoding**. Secured video
> content delivery using RSA-signed URLs to prevent unauthorized access and scraping.

---

## 📁 Project Structure

```
mininetflix/
├── backend/
│   ├── src/main/java/com/mininetflix/
│   │   ├── config/          # AWS, Security configs
│   │   ├── controller/      # AuthController, VideoController
│   │   ├── dto/             # Request/Response DTOs
│   │   ├── exception/       # GlobalExceptionHandler
│   │   ├── model/           # User, Video entities
│   │   ├── ratelimit/       # RateLimitService
│   │   ├── repository/      # JPA repositories
│   │   ├── security/        # JWT, Auth filter
│   │   └── service/         # Auth, Video, S3, SQS, MediaConvert
│   ├── Dockerfile
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── api/             # axios client (endpoint contract)
│   │   ├── components/      # Navbar, VideoCard
│   │   ├── context/         # AuthContext
│   │   └── pages/           # Login, Register, Dashboard, Upload, Player
│   ├── Dockerfile
│   ├── nginx.conf
│   └── package.json
│
├── infrastructure/
│   └── cloudformation.yml   # S3, SQS, DLQ, CloudFront, IAM
│
├── docker-compose.yml
├── .env.example
└── README.md
```
