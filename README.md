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
   └── State Machine (VideoStatus)
        ↓
   [S3 Input Bucket]   ← direct upload (no backend bottleneck)
        ↓
   [SQS FIFO Queue]   ← decouples upload from processing
   + [DLQ]            ← dead letter queue for failed jobs
        ↓
   [AWS MediaConvert]  ← professional transcoding
   ├── H.264 encoding
   ├── Multi-resolution: 1080p / 720p / 480p
   └── HLS segmentation (6-second chunks)
        ↓
   [S3 Output Bucket]
        ↓
   [CloudFront CDN]    ← global low-latency delivery
        ↓
   [React Player + HLS.js]  ← adaptive bitrate streaming
```

---

## 🚀 Quick Start (Local Dev)

### Prerequisites
- Java 17+, Maven 3.9+
- Node.js 20+
- Docker & Docker Compose
- AWS account with configured credentials

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
```

### Step 2 — Configure Environment

```bash
cp .env.example .env
# Fill in your AWS credentials, bucket names, etc.
```

### Step 3 — Run with Docker Compose

```bash
docker-compose up --build
```

- Frontend: http://localhost:3000
- Backend: http://localhost:8080
- H2 Console (dev): http://localhost:8080/h2-console

### Step 3 (Alternative) — Run Separately

**Backend:**
```bash
cd backend

# For local dev (H2 in-memory DB, no AWS):
export JWT_SECRET=local-dev-secret-key-minimum-256-bits-long
mvn spring-boot:run

# With PostgreSQL + AWS:
export DB_URL=jdbc:postgresql://localhost:5432/mininetflix
export DB_USERNAME=mininetflix
export DB_PASSWORD=mininetflix123
export AWS_ACCESS_KEY_ID=your_key
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
- Free tier: 3 uploads/day, 500MB max file
- Pro tier: 50 uploads/day, 5GB max file
- Implemented via DB query (daily count) + future Bucket4j/Redis support

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
- Presigned URLs with 5-min expiry
- File type validation (video/* only)
- IAM least privilege (MediaConvert role scoped to specific buckets)
- CORS configured for known origins only
- Non-root Docker user

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
| **Total** | **~$5.23/month** |

---

## 🎯 Resume Bullet Point

> Designed and implemented a scalable video processing pipeline using **Spring Boot**, **React**,
> **S3 presigned URLs**, **SQS**, and **AWS MediaConvert** with **HLS adaptive bitrate streaming**
> via **CloudFront CDN**, featuring **JWT auth**, **rate limiting**, **idempotent job creation**,
> **DLQ fault tolerance**, and **cost-aware smart encoding** that generates only required resolutions.

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
