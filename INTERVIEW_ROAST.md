# 🔥 INTERVIEW ROAST FILE — MiniNetflix Video Processing Pipeline

> **Roast Level**: Interviewer is a Senior Eng at Netflix/YouTube who knows everything.
> Your job: Sound like you designed this yourself. Speak confidently. Never fumble these.

---

## SECTION 1 — THE FLOW (Tell Me What This Project Does)

### 30-Second Pitch
> "I built a production-grade video processing pipeline inspired by Netflix. You upload a raw video, it gets stored in S3, transcoded into adaptive HLS format by AWS MediaConvert at multiple resolutions (1080p, 720p, 480p), and served globally via CloudFront with time-limited signed URLs. The backend is Spring Boot. Frontend is React with HLS.js for adaptive streaming."

---

### Complete Flow Walkthrough (Step by Step)

#### Phase 1 — Authentication
1. User registers → backend hashes password with BCrypt, saves to PostgreSQL, returns a JWT
2. JWT is stored in `localStorage` on the client
3. Every subsequent request carries `Authorization: Bearer <token>` header
4. `JwtAuthFilter` (Spring Security `OncePerRequestFilter`) intercepts all requests, validates JWT, loads user from DB, sets `SecurityContext`

#### Phase 2 — Upload Request
1. User selects video → frontend calls `POST /api/videos/upload-url`
2. Backend runs **two checks** via `RateLimitService`:
   - Upload count for today vs. tier limit (FREE: 3/day, PRO: 50/day)
   - File size vs. tier limit (FREE: 500 MB, PRO: 5 GB)
3. Backend creates a `Video` record in PostgreSQL with `status = UPLOADED`
4. Backend generates a **presigned S3 PUT URL** (5-min expiry) using `S3Presigner` from AWS SDK v2
5. Returns `{videoId, uploadUrl, s3Key, expiresInSeconds}` to frontend

#### Phase 3 — Direct S3 Upload (The Key Design)
1. Frontend uploads directly to S3 using `XMLHttpRequest` (not axios, for progress tracking)
2. **Backend never touches the video bytes** — this is critical for scalability
3. Progress bar shown via `xhr.upload.onprogress`

#### Phase 4 — Trigger Processing
1. After S3 upload completes, frontend calls `POST /api/videos/{videoId}/confirm`
2. Backend:
   - Updates `Video.status → QUEUED`
   - Sends a message to **SQS FIFO queue** with `{videoId, s3Key, userId, inputBucket}`
   - `messageDeduplicationId = videoId` (idempotency — no duplicate jobs)
   - `messageGroupId = "video-processing"` (FIFO ordering)
   - Immediately calls `triggerMediaConvert()` → creates job in AWS MediaConvert
   - Saves the returned `jobId` to `Video.mediaConvertJobId`, sets `status → PROCESSING`

#### Phase 5 — Transcoding
1. AWS MediaConvert reads from S3 Input bucket (using an IAM role with `s3:GetObject`)
2. **Smart encoding**: Only generates resolutions ≤ input resolution
   - Input is 1080p → generates 1080p + 720p + 480p
   - Input is 480p → generates only 480p (no upscaling)
3. Outputs HLS format: master playlist + per-resolution playlists + 6-second `.ts` segments
4. Writes everything to S3 Output bucket prefix: `processed/{videoId}/`

#### Phase 6 — Status Update
1. **Primary path**: CloudWatch Events fires on job completion → hits `POST /api/videos/webhook/mediaconvert`
2. Backend looks up `Video` by `mediaConvertJobId`, sets `status → READY` or `FAILED`
3. **Fallback path**: `@Scheduled` job runs every 2 minutes, finds stuck `PROCESSING/QUEUED` videos > 10 min, polls MediaConvert directly

#### Phase 7 — Streaming
1. Frontend calls `GET /api/videos/{videoId}/stream`
2. Backend searches S3 Output bucket for the master `.m3u8` playlist (detection logic: master's base name is a prefix of variant names)
3. Backend **signs the URL** with RSA-2048 using CloudFront Custom Policy
4. Returns signed master playlist URL to frontend
5. `PlayerPage` initialises HLS.js with the signed URL
6. Custom `SignedUrlLoader` propagates CloudFront query params (`Policy`, `Signature`, `Key-Pair-Id`) to every segment request
7. HLS.js auto-selects quality based on bandwidth (ABR — Adaptive Bitrate)

---

## SECTION 2 — WHY THIS WAY?

### Q: Why presigned S3 URLs instead of uploading through your backend?
**A:**
> If video bytes go through Spring Boot, one upload hogs the server's bandwidth and memory. A 1 GB video upload would block that thread for minutes. With presigned URLs, the client talks directly to S3 — backend just gives permission and leaves. This means:
> - Zero backend I/O cost
> - No memory pressure
> - Backend stays horizontally scalable
> - Massive files work fine
> This is exactly how YouTube, Netflix, Dropbox handle uploads at scale.

---

### Q: Why SQS between upload and transcoding?
**A:**
> Without a queue, if MediaConvert is slow or throws an error, the upload request hangs or fails — bad UX. With SQS:
> - Upload and processing are **decoupled** — they fail/succeed independently
> - **SQS FIFO** guarantees no duplicate processing (via `messageDeduplicationId = videoId`)
> - **Dead Letter Queue (DLQ)** catches jobs that fail 3 times — nothing is silently lost
> - You can scale consumers independently of producers
> This is the standard fan-out/queue pattern for async workloads.

---

### Q: Why HLS (HTTP Live Streaming) instead of MP4?
**A:**
> MP4 requires sending the whole file. With HLS:
> - Video is split into 6-second `.ts` chunks
> - Client only downloads what it's currently watching
> - **Adaptive Bitrate (ABR)**: HLS.js automatically switches between 1080p/720p/480p based on current bandwidth — no buffering on slow networks
> - CloudFront caches individual segments, not the whole file — much more cache-efficient
> - iOS supports HLS natively

---

### Q: Why CloudFront Signed URLs instead of just serving S3 publicly?
**A:**
> Two reasons:
> 1. **Prevent scraping**: A public URL can be shared, scraped, and hot-linked. A signed URL expires in 1 hour.
> 2. **Piracy protection**: Nobody can embed your video in their own player with a URL that expires.
>
> We use **Custom Policy** (not Canned Policy) because CloudFront Custom Policy allows a **wildcard resource** like `processed/{videoId}/*` — meaning one signature covers the master playlist AND all the segment files. With Canned Policy you can only sign one specific URL.

---

### Q: Why PostgreSQL and not DynamoDB?
**A:**
> Rate limiting needs `countTodayUploads()` — that's a `COUNT` + `WHERE date = today` query. Very natural in SQL. In DynamoDB you'd have to design a secondary index or scan items and filter client-side — excessive complexity for something SQL handles natively in a single line. Also, user and video data is highly relational — joins, foreign keys, transactions. PostgreSQL via Supabase gives us all that for free.

---

### Q: Why Redis for rate limiting?
**A:**
> If you stored rate limit state in the database, every upload check would need a DB write + query. Redis reads/writes in microseconds. More importantly: when you scale to multiple backend instances, they all share the same Redis — the rate limit is **distributed**. Without Redis, user could hit 3 different instances and upload 9 times (3 × 3). Redis makes it correct regardless of how many backend pods are running.

---

### Q: Why FIFO SQS instead of standard SQS?
**A:**
> Standard SQS can deliver messages **out of order** and potentially **more than once**. For video processing:
> - We use `messageDeduplicationId = videoId`, so even if the same video is confirmed twice, MediaConvert only gets one job
> - `messageGroupId = "video-processing"` keeps messages ordered within the group
> FIFO + deduplication = idempotent job creation. This is production correctness.

---

### Q: Why soft deletes?
**A:**
> Hard deletes are permanent. If a user accidentally deletes a video, if a bug causes a cascade delete, or if a regulator asks for data — it's gone. Soft delete (`status = DELETED`) means the row stays in the database, and we filter it out in queries with `status != DELETED`. You can always undo it.

---

### Q: Why smart encoding (resolution-aware)?
**A:**
> MediaConvert charges per minute of output video. If someone uploads a 480p video and you transcode it to 1080p, you're:
> 1. Paying 3× the transcoding cost
> 2. Producing a file with no visual improvement (upscaling adds no quality)
> The code checks `originalResolution` and only generates outputs ≤ that resolution. Direct cost optimization.

---

## SECTION 3 — WHAT PROBLEMS DID YOU FACE?

### Problem 1 — Master Playlist Detection
**Problem**: AWS MediaConvert names HLS playlists based on the input filename, NOT `master.m3u8`. So after transcoding `funny_cat.mp4`, you get `funny_cat.m3u8` (master), `funny_cat_720p.m3u8`, `funny_cat_480p.m3u8` — not `master.m3u8`.

**How I solved it**: Listed all `.m3u8` files in the output prefix and applied a detection algorithm:
> The master playlist's base name is always a **prefix** of variant playlist names (master: `video.m3u8`, variant: `video_480p.m3u8`). Find the `.m3u8` whose base name is a prefix of at least one other `.m3u8`. If only one `.m3u8` exists, that is both the master and single variant.

---

### Problem 2 — HLS.js Not Propagating Signed URL Query Params to Segments
**Problem**: When HLS.js loads the master playlist URL (which has CloudFront signature params appended as `?Policy=...&Signature=...&Key-Pair-Id=...`), it then requests individual segments using **relative paths** from the playlist. Those relative paths don't carry the signature query params → CloudFront returns 403 Forbidden on every segment.

**How I solved it**: Wrote a custom `SignedUrlLoader` class that extends the HLS.js default loader:
```javascript
class SignedUrlLoader extends Hls.DefaultConfig.loader {
    load(context, config, callbacks) {
        // If master URL had query params, append them to every segment request
        const masterUrl = new URL(window.hlsSignedUrl)
        const query = masterUrl.search  // "?Policy=...&Signature=..."
        if (query && !context.url.includes('Policy=')) {
            const separator = context.url.includes('?') ? '&' : '?'
            context.url += separator + query.substring(1)
        }
        super.load(context, config, callbacks)
    }
}
```

---

### Problem 3 — Circular Dependency in Spring Security
**Problem**: `SecurityConfig` needed `JwtAuthFilter`, and `JwtAuthFilter` needed `UserDetailsService`, which was defined inside `SecurityConfig` → circular bean dependency.

**How I solved it**: Used `@Lazy` on the `JwtAuthFilter` constructor injection in `SecurityConfig`:
```java
public SecurityConfig(@Lazy JwtAuthFilter jwtAuthFilter, UserRepository userRepository)
```
`@Lazy` means Spring doesn't create the bean immediately — it creates a proxy and injects the real bean on first use, breaking the circular chain.

---

### Problem 4 — CloudFront 403 on Signed URLs
**Problem**: Getting 403 even with correctly generated signed URLs. Turned out CloudFront OAI (Origin Access Identity) was not properly restricting S3 access, and the bucket policy was conflicting.

**How I solved it**: Used OAC (Origin Access Control) instead of the older OAI. Set S3 bucket policy to only allow `cloudfront.amazonaws.com` as principal. Verified the RSA key pair was uploaded to CloudFront as a **Public Key** under Key Groups, not under the older CloudFront key pairs section.

---

### Problem 5 — Rate Limiting Not Horizontal-Safe
**Problem**: Initial implementation stored upload count in the `User` entity in PostgreSQL. Under concurrent requests (multiple browser tabs), race conditions allowed more uploads than the limit.

**How I solved it**: Used `countTodayUploads()` JPQL query + Redis-backed Bucket4j for the actual token bucket enforcement. The count check is now atomic per user key in Redis.

---

## SECTION 4 — HOW DO YOU SIGN THE URL?

This is a deep question. Here's the full answer:

### What is URL Signing?
A signed URL is a URL with a **cryptographic signature** attached. CloudFront validates the signature before serving content. Only the private key holder (your backend) can generate valid signatures.

### The Two Types

| Policy | Resource | Expiry | Use Case |
|--------|----------|--------|----------|
| Canned Policy | Single exact URL | Fixed date | Single file access |
| **Custom Policy** | Wildcard URL pattern | Flexible | **Multiple files under same path** |

We use **Custom Policy** because the master playlist and all its segment files live under `processed/{videoId}/`. One signature covers all of them.

### The RSA Key Pair
- `private_key.pem` — kept on the backend server (NEVER shared)
- `public_key.pem` — uploaded to AWS CloudFront as a "Public Key"

CloudFront stores your public key. When a signed URL arrives, CloudFront verifies the signature using your public key. Only your private key could have created that signature (RSA asymmetry).

### The Exact Code

```java
// S3Service.java — signUrl()
public String signUrl(String masterPlaylistUrl) {
    // 1. Make resource URL a wildcard so ALL segments under this video path are covered
    //    masterPlaylistUrl: https://d1abc.cloudfront.net/processed/UUID/master.m3u8
    //    resourcePath:      https://d1abc.cloudfront.net/processed/UUID/*
    String resourcePath = masterPlaylistUrl.substring(0, masterPlaylistUrl.lastIndexOf('/') + 1) + "*";

    // 2. Set expiry: 6 hours from now
    Instant expirationDate = Instant.now().plus(6, ChronoUnit.HOURS);

    // 3. Build the CloudFront Custom Policy signing request
    CloudFrontUtilities cloudFrontUtilities = CloudFrontUtilities.create();
    CustomSignerRequest customSignerRequest = CustomSignerRequest.builder()
            .resourceUrl(resourcePath)          // wildcard resource
            .privateKey(loadPrivateKey(...))    // RSA-2048 private key from .pem file
            .keyPairId(cloudFrontKeyPairId)     // The ID of your uploaded public key in CloudFront
            .expirationDate(expirationDate)     // URL validity window
            .build();

    // 4. Sign it — AWS SDK generates: Policy (Base64 JSON) + Signature (RSA-SHA1) + Key-Pair-Id
    SignedUrl signedUrl = cloudFrontUtilities.getSignedUrlWithCustomPolicy(customSignerRequest);

    // 5. Extract the query params from signed URL and append to the actual master playlist URL
    //    (because signed URL uses the resource, not the actual file URL)
    String signatureQuery = signedUrl.url().substring(signedUrl.url().indexOf('?') + 1);
    return masterPlaylistUrl + "?" + signatureQuery;   // master.m3u8?Policy=...&Signature=...&Key-Pair-Id=...
}
```

### Loading the Private Key

```java
private PrivateKey loadPrivateKey(String path) throws Exception {
    String keyContent = Files.readString(Paths.get(path));  // Read .pem file

    // Strip PEM headers/footers and newlines — RSA library needs raw Base64
    String privateKeyPEM = keyContent
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

    // Decode Base64 → raw DER bytes → RSA PrivateKey object
    byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
    return keyFactory.generatePrivate(keySpec);
}
```

### What does the signed URL actually look like?
```
https://d1abc.cloudfront.net/processed/UUID/master.m3u8
  ?Policy=eyJTdGF0ZW1...   ← Base64-encoded JSON {resource, expiry}
  &Signature=ABC123xyz...  ← RSA-SHA1 signature of the Policy
  &Key-Pair-Id=K1234567890 ← Tells CloudFront which public key to verify with
```

---

## SECTION 5 — THE PEM FILES (private_key.pem / public_key.pem)

### Why are they in local but NOT in the GitHub repo?

**Short answer**: They are secrets. Committing a private key to Git is a catastrophic security mistake. Anyone who clones your repo can sign their own CloudFront URLs and access all your videos for free.

The `.gitignore` (or simple discipline) keeps them out of the repo. They are referenced via environment variable:
```yaml
# application.yml
aws:
  cloudfront:
    private-key-path: ${CLOUDFRONT_PRIVATE_KEY_PATH:}
```
On the server, you set `CLOUDFRONT_PRIVATE_KEY_PATH=/path/to/private_key.pem`. The file lives on the machine, not in code.

---

### What are they used for?
| File | Stored Where | Used For |
|------|-------------|----------|
| `private_key.pem` | Backend server only | Signing CloudFront URLs (RSA operation) |
| `public_key.pem` | AWS CloudFront console | Verifying signatures on incoming requests |

**Flow**:
- Backend uses `private_key.pem` → generates `Signature` query param
- CloudFront uses your uploaded public key → verifies the `Signature`
- If valid → serves the video. If not → 403 Forbidden.

---

### How do you generate them?

```bash
# Step 1: Generate 2048-bit RSA private key (PKCS#8 format)
openssl genrsa -out private_key.pem 2048

# Step 2: Extract the public key from the private key
openssl rsa -pubout -in private_key.pem -out public_key.pem
```

Then:
1. Go to **AWS Console → CloudFront → Key Management → Public Keys**
2. Click "Create public key", paste contents of `public_key.pem`
3. Note the **Public Key ID** (e.g., `K1ABCDEFGHIJK`)
4. Go to **Key Groups**, create one, add your public key to it
5. Attach the Key Group to your CloudFront distribution's cache behavior
6. Your `CLOUDFRONT_KEY_PAIR_ID` env var = that Public Key ID

> **Do you need OpenSSL installed locally?** Yes — but only once, during initial setup. Once the keys are generated, you never need to do it again. The backend reads the `.pem` file at runtime; it does NOT call OpenSSL at runtime. Java's `KeyFactory` natively handles PKCS8 format.

---

### If the PEM file is not present / not configured — what happens?

The `signUrl()` method has a try-catch:
```java
} catch (Exception e) {
    log.error("Failed to sign URL. KeyPairId: {}, Path: {}", ...);
    return masterPlaylistUrl;  // ← Returns UNSIGNED URL as fallback
}
```

So the system **degrades gracefully** — streaming still works, but URLs are not signed (less secure). In production you'd want to fail loudly here instead. This fallback is a dev convenience.

---

## SECTION 6 — TWISTED HIDDEN QUESTIONS 🎯

These are the questions interviewers ask to check if you **actually built this** or just copy-pasted it.

---

### 🔀 Q: SQS sends the job, and then you immediately call MediaConvert from the same thread. What's the point of SQS then?

**Trap**: It looks like SQS is doing nothing if backend directly calls MediaConvert right after.

**Answer**:
> The current design uses SQS as a **record-of-intent** and has the Spring backend itself trigger MediaConvert — this is a monolithic processing pattern. In a true event-driven architecture, a separate **Lambda function** would consume the SQS message and call MediaConvert asynchronously. The SQS message gives you:
> 1. **Fault tolerance**: If MediaConvert call fails, the SQS message is not acknowledged and gets retried (up to 3 times before DLQ)
> 2. **Decoupling path**: You can replace the Spring-triggered processing with a Lambda consumer without changing the upload API
> 3. **DLQ safety net**: Failed jobs land in DLQ where they can be inspected and reprocessed
>
> The current implementation is a pragmatic hybrid — backend triggers MediaConvert directly but also queues the message for the retry/DLQ safety net.

---

### 🔀 Q: You said you use HLS Custom Policy because it covers `processed/{videoId}/*`. But then why does your signed URL still only sign the master playlist URL path, not the wildcard?

**Trap**: Understanding the difference between the `resourceUrl` (the wildcard used for signing) vs. the URL actually returned to the client.

**Answer**:
> The `resourceUrl` in the Custom Policy IS the wildcard: `https://d1.cloudfront.net/processed/{UUID}/*`. AWS signs THIS wildcard — meaning CloudFront will accept any URL matching that pattern.
>
> But we return to the client: `masterPlaylistUrl + "?" + signatureParams` — that's the actual master `.m3u8` URL with the signature appended.
>
> When HLS.js then requests `/processed/{UUID}/segment_001.ts` (substituting the segment path), the Custom Policy's wildcard scope covers it. The `SignedUrlLoader` propagates the same query params (`Policy`, `Signature`, `Key-Pair-Id`) to every segment request, and CloudFront validates: "does this URL match `processed/{UUID}/*`?" → Yes → 200 OK.

---

### 🔀 Q: What happens if the same video is uploaded twice to S3 with the same videoId?

**Trap**: Tests your understanding of idempotency.

**Answer**:
> The `s3Key` is constructed as `uploads/{userId}/{UUID}/{sanitized_filename}`. The `videoId` (UUID) is generated fresh per upload request. So two separate upload requests always get new UUIDs → different S3 keys → no collision.
>
> But if the CONFIRM endpoint is called twice for the same `videoId`, the code sends an SQS message with `messageDeduplicationId = videoId`. FIFO SQS deduplicates messages with the same ID within a 5-minute window. Even if two confirmations race through, only one MediaConvert job is created.

---

### 🔀 Q: Your Rate Limiting uses `countTodayUploads()` from PostgreSQL, not Redis/Bucket4j directly. How is that Redis rate-limited then?

**Trap**: `pom.xml` includes `bucket4j-redis` but the actual `RateLimitService.java` queries the DB. Is Redis actually used for rate limiting?

**Answer**:
> Good catch. The current `RateLimitService` counts today's uploads from PostgreSQL using a JPQL query. The `bucket4j-redis` dependency was included in `pom.xml` in preparation for a distributed Token Bucket implementation — but the actual rate limiting logic is currently **DB-based, not Redis-based**. Redis IS configured and running (used by Spring Data Redis), but the Bucket4j token bucket integration in `RateLimitService` is not wired up in this version.
>
> In production you'd replace the `countTodayUploads()` approach with a Bucket4j `RedisProxyManager` backed bucket keyed by `userId` — that gives sub-millisecond enforcement and true distributed correctness. The current DB-based check works fine for a single-instance deployment.

---

### 🔀 Q: JWT is stored in localStorage. Isn't that vulnerable to XSS attacks?

**Trap**: Classic security question.

**Answer**:
> Yes, `localStorage` is accessible to any JavaScript on the page, which means if there's an XSS vulnerability, an attacker can steal the token. The alternative is `httpOnly` cookies — which JavaScript can't read, so they're XSS-safe. However, cookies introduce CSRF vulnerability (mitigated with `SameSite=Strict` or CSRF tokens).
>
> For this project, `localStorage` was chosen for simplicity — it's easy to attach to every Axios request via the interceptor. In a production fintech/healthcare app, I'd switch to `httpOnly` cookies with `SameSite=Strict` to eliminate both XSS and CSRF risks simultaneously.

---

### 🔀 Q: What happens if MediaConvert finishes but the webhook never arrives? How long does the video stay stuck in PROCESSING?

**Answer**:
> The `pollProcessingJobs()` method, annotated with `@Scheduled(fixedDelay = 120000)` (every 2 minutes), queries for videos in `PROCESSING` or `QUEUED` status that were created more than 10 minutes ago. For each, it calls `MediaConvertService.getJobStatus(jobId)` directly via the AWS SDK and updates the DB. So a video can be stuck for at most ~12 minutes before the poller catches it. This is the fallback — the webhook is the fast path.

---

### 🔀 Q: How does HLS.js select between 1080p and 480p? Does your backend decide?

**Answer**:
> The backend has nothing to do with quality selection during playback. The **master HLS playlist** lists all available variant streams with their bandwidth:
> ```
> #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
> video_1080p.m3u8
> #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
> video_720p.m3u8
> #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480
> video_480p.m3u8
> ```
> HLS.js measures the client's actual download speed for the first segment, then switches variants automatically using its ABR (Adaptive Bitrate) algorithm. On a fast connection it stays at 1080p; on a slow connection it steps down. Backend is completely uninvolved.

---

### 🔀 Q: User deletes a video — what actually gets deleted?

**Answer**:
> It's a **soft delete** — `Video.status` is set to `DELETED` in PostgreSQL. Nothing is removed from S3. The video won't show up in `getUserVideos()` because queries filter `status != DELETED`.
>
> The `deleteFolder()` method in `S3Service` exists and CAN delete all S3 objects under `processed/{videoId}/` in bulk using paginated `deleteObjects()` — but it's currently only called in the service layer for **hard deletion** scenarios, not the user-facing DELETE endpoint. This is intentional — S3 has lifecycle rules (Glacier after 30 days) for cost management, and soft deletes let you audit and recover data.

---

### 🔀 Q: Why `@Lazy` on JwtAuthFilter — couldn't you have just refactored?

**Answer**:
> Yes. The cleaner fix is to extract `UserDetailsService` into its own `@Service` class instead of defining it as a `@Bean` inside `SecurityConfig`. That way `JwtAuthFilter` → `UserDetailsService` (separate bean) → `UserRepository`, no cycle. `@Lazy` is a quick pragmatic fix that works fine but hides the design issue. In a team setting I'd do the proper refactor to make the dependency graph explicit.

---

### 🔀 Q: Can two FREE-tier users be told they have uploads remaining, but both try to upload simultaneously and exceed the limit?

**Answer**:
> Yes — this is a **TOCTOU (Time-of-Check-Time-of-Use) race condition**. Both users pass the `checkUploadLimit()` check (which reads from DB), both get presigned URLs, both upload, now both have used their full day's allowance but one snuck through.
>
> The fix is to use `SELECT ... FOR UPDATE` (pessimistic locking) or an atomic increment in Redis (`INCR user:{id}:uploads:today`) with a per-day TTL, and only allow the upload if the resulting count ≤ limit. Bucket4j with Redis handles this atomically. Current implementation has this gap — worth acknowledging proactively.

---

*End of Roast File. If you can answer all of these confidently, you will outperform 95% of candidates presenting similar projects.*
