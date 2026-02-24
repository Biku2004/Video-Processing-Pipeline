# ☁️ AWS.md — MiniNetflix AWS Deep Dive

> **Purpose**: Complete AWS usage reference + Roast-Level interview questions for every AWS service used in this project.
> Real values from the actual deployed stack are embedded throughout.

---

## Your Actual AWS Stack (Real Values)

| Resource | Value |
|----------|-------|
| AWS Region | `ap-south-1` (Mumbai) |
| Account ID | `654654527604` |
| S3 Input Bucket | `mininetflix-input-654654527604` |
| S3 Output Bucket | `mininetflix-output-654654527604` |
| SQS FIFO Queue | `https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs.fifo` |
| SQS DLQ | `https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo` |
| MediaConvert Role | `arn:aws:iam::654654527604:role/mininetflix-mediaconvert-role` |
| MediaConvert Endpoint | `https://mediaconvert.ap-south-1.amazonaws.com` |
| CloudFront Domain | `https://d1u0dn8mhvuadb.cloudfront.net` |

---

## Full AWS Architecture Flow

```
User Browser
    │
    │ 1. POST /api/videos/upload-url
    ▼
Spring Boot API (Port 8080)
    │
    │ 2. Generate Presigned PUT URL (S3Presigner, 5-min expiry)
    ▼
Amazon S3 — Input Bucket [mininetflix-input-654654527604]
    │   ← User uploads directly here (XHR PUT — backend bypassed)
    │
    │ 3. POST /api/videos/{id}/confirm  →  Backend queues job
    ▼
Amazon SQS FIFO [mininetflix-jobs.fifo]
    │   ← message: {videoId, s3Key, userId, inputBucket}
    │   ← messageDeduplicationId = videoId (no duplicate jobs)
    │
    │ (after 3 failures → DLQ)
    ▼
AWS MediaConvert
    │   ← IAM Role: mininetflix-mediaconvert-role
    │   ← Reads from: S3 Input Bucket
    │   ← Outputs: HLS (1080p + 720p + 480p), .ts segments, .m3u8 playlists
    ▼
Amazon S3 — Output Bucket [mininetflix-output-654654527604]
    │   prefix: processed/{videoId}/
    │
    │ (job status change → CloudWatch Event → webhook)
    ▼
CloudWatch Events → POST /api/videos/webhook/mediaconvert
    │   ← updates Video.status = READY in PostgreSQL
    ▼
Amazon CloudFront [d1u0dn8mhvuadb.cloudfront.net]
    │   ← Origin: S3 Output Bucket (OAC — Origin Access Control)
    │   ← Signed URLs: RSA-2048 Custom Policy, 6h expiry
    │   ← HLS playlists: no-cache (DefaultTTL=0)
    │   ← Segments (.ts): cache-optimized
    ▼
React Player (HLS.js)
    ← Adaptive Bitrate: auto-selects 1080p/720p/480p by bandwidth
    ← Custom SignedUrlLoader: propagates Policy+Signature to every segment
```

---

## SERVICE 1 — AWS CloudFormation

### What it does here
Provisions the entire AWS infrastructure as a single stack — S3 buckets, SQS queues, DLQ, IAM role, CloudFront distribution, and CloudWatch alarm. One command, everything is created.

### Deploy the Stack

```powershell
# You MUST be inside the infrastructure folder (cloudformation.yml lives there)
cd "D:\Study\Udemy\New folder\Personal extra-projects\mininetflix-project\mininetflix\infrastructure"

aws cloudformation deploy `
  --template-file cloudformation.yml `
  --stack-name mininetflix `
  --capabilities CAPABILITY_NAMED_IAM `
  --region ap-south-1
```

> ⚠️ `CAPABILITY_NAMED_IAM` is required because the template creates an IAM role (`mininetflix-mediaconvert-role`). AWS forces you to explicitly acknowledge that you're creating IAM resources.

### View Stack Outputs (Your Real Values)

```powershell
aws cloudformation describe-stacks `
  --stack-name mininetflix `
  --region ap-south-1 `
  --query "Stacks[0].Outputs" `
  --output table
```

**Output table shows:**
```
InputBucketName        → mininetflix-input-654654527604
OutputBucketName       → mininetflix-output-654654527604
VideoJobsQueueUrl      → https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs.fifo
VideoJobsDLQUrl        → https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo
MediaConvertRoleArn    → arn:aws:iam::654654527604:role/mininetflix-mediaconvert-role
CloudFrontDomain       → https://d1u0dn8mhvuadb.cloudfront.net
```

### Check Stack Status

```powershell
aws cloudformation describe-stacks `
  --stack-name mininetflix `
  --region ap-south-1 `
  --query "Stacks[0].StackStatus"
```

Common statuses: `CREATE_COMPLETE | UPDATE_COMPLETE | DELETE_IN_PROGRESS | ROLLBACK_COMPLETE`

### Delete the Stack (Stop All Charges)

> ⚠️ S3 buckets must be empty before deletion or CloudFormation will fail.

```powershell
# Step 1: Get account ID
$accountId = aws sts get-caller-identity --query Account --output text

# Step 2: Empty both S3 buckets
aws s3 rm s3://mininetflix-input-$accountId --recursive --region ap-south-1
aws s3 rm s3://mininetflix-output-$accountId --recursive --region ap-south-1

# Step 3: Delete the stack
aws cloudformation delete-stack --stack-name mininetflix --region ap-south-1

# Step 4: Watch deletion progress
aws cloudformation describe-stacks `
  --stack-name mininetflix `
  --region ap-south-1 `
  --query "Stacks[0].StackStatus"
# DELETE_IN_PROGRESS → DELETE_COMPLETE
```

### 🔥 Roast Questions — CloudFormation

**Q: What is `CAPABILITY_NAMED_IAM` and why is it needed?**
> AWS requires explicit acknowledgment when a CloudFormation template creates IAM resources with custom names. Without this flag, the deploy fails with `InsufficientCapabilitiesException`. It's a safety gate — AWS wants you to consciously consent to IAM changes because they can grant powerful permissions.

**Q: What happens if your stack deploy fails midway?**
> CloudFormation auto-rolls back. All resources created so far are deleted. You get `ROLLBACK_COMPLETE` status. Your template is atomic — either everything is created or nothing is. Check the "Events" tab in the Console or:
> ```powershell
> aws cloudformation describe-stack-events --stack-name mininetflix --region ap-south-1
> ```

**Q: Why do you even use CloudFormation instead of just creating resources manually?**
> Infrastructure as Code (IaC). It's reproducible, version-controlled, and self-documenting. If the entire AWS account is nuked tomorrow, you run one command and everything is back in exactly the same state. Manual clicking in the console is not reproducible — you forget steps, misconfigure things, and can't review changes in a PR.

**Q: Why did your first CloudFormation deploy fail with "Invalid template path"?**
> Real issue from the project. `cloudformation.yml` lives inside `infrastructure/`, not the root. When running from the root:
> ```powershell
> # WRONG — run from root
> aws cloudformation deploy --template-file cloudformation.yml ...
> 
> # RIGHT — navigate first
> cd .\infrastructure\
> aws cloudformation deploy --template-file cloudformation.yml ...
> ```

---

## SERVICE 2 — Amazon S3

### What it does here

| Bucket | Role | Lifecycle Rule |
|--------|------|---------------|
| `mininetflix-input-*` | Receives raw video uploads via presigned PUT URL | **Delete after 7 days** (raw files not needed after transcoding) |
| `mininetflix-output-*` | Stores processed HLS output (playlists + .ts segments) | **Move to Glacier after 30 days** (cost optimization) |

### Key CLI Commands

```powershell
# List all objects in output bucket (see processed videos)
aws s3 ls s3://mininetflix-output-654654527604/processed/ --region ap-south-1

# List a specific video's output files
aws s3 ls s3://mininetflix-output-654654527604/processed/{videoId}/ --region ap-south-1 --recursive

# Empty input bucket (before stack deletion)
aws s3 rm s3://mininetflix-input-654654527604 --recursive --region ap-south-1

# Empty output bucket
aws s3 rm s3://mininetflix-output-654654527604 --recursive --region ap-south-1

# Download a specific processed file to verify
aws s3 cp s3://mininetflix-output-654654527604/processed/{videoId}/master.m3u8 ./test.m3u8 --region ap-south-1
```

### How Presigned URLs Work (Step by Step)

**Backend code in `S3Service.java`:**
```java
// 1. Define WHAT you want to allow: PUT to this specific key
PutObjectRequest objectRequest = PutObjectRequest.builder()
    .bucket(inputBucket)         // mininetflix-input-654654527604
    .key(s3Key)                  // uploads/{userId}/{UUID}/{filename}
    .contentType(contentType)    // video/mp4
    .metadata(Map.of("video-id", videoId, "user-id", userId))
    .build();

// 2. Define HOW LONG the permission lasts
PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
    .signatureDuration(Duration.ofSeconds(300))   // 5 minutes
    .putObjectRequest(objectRequest)
    .build();

// 3. Create the signed URL — AWS SDK signs it with your credentials
PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
return presignedRequest.url().toString();
```

**Frontend then does (from `client.js`):**
```javascript
// Direct PUT to S3 — backend is NOT involved in this request at all
xhr.open('PUT', presignedUrl)
xhr.setRequestHeader('Content-Type', file.type)
xhr.send(file)   // Raw binary video bytes go straight to S3
```

### 🔥 Roast Questions — S3

**Q: What is a presigned URL? How does S3 know it's valid if the backend isn't involved?**
> A presigned URL is a regular HTTPS URL with your AWS credentials embedded as query parameters — an HMAC-SHA256 signature computed from: the bucket, key, HTTP method, expiry time, and your secret access key. When the client sends a PUT to that URL, S3 recomputes the signature using the same inputs and your key. If they match, S3 allows the operation. The backend's credentials are "baked in" — no backend involvement needed at request time.

**Q: The presigned URL expires in 5 minutes. What happens if the user's upload takes longer?**
> Then the upload fails with a 403 from S3. The URL's expiry is for the **start** of the request, not the completion. Once S3 accepts the first byte (request started within 5 minutes), the upload continues to completion. A 500MB upload typically takes longer than 5 minutes, but since the connection is established early, it's fine in practice. If needed, you could increase `presigned-url-expiry` in `application.yml`.

**Q: Why delete raw input files after 7 days?**
> Cost. S3 charges ~$0.023/GB/month. After MediaConvert finishes, the raw file serves no purpose — the processed HLS output is in the output bucket. The 7-day window gives a safety margin in case something needs to be re-processed. After that, let the lifecycle rule clean it up automatically.

**Q: Why move output to Glacier after 30 days?**
> Glacier costs ~$0.004/GB/month vs S3 Standard's ~$0.023/GB. Older videos are rarely watched — moving them to Glacier saves ~83% storage cost. The trade-off: Glacier retrieval takes minutes to hours. This is acceptable for videos not frequently accessed. Heat-based tiering (S3 Intelligent-Tiering) would be the next evolution.

**Q: S3 CORS is configured only for localhost origins. How would this work in production?**
> The `CORS_ORIGINS` environment variable is injected at runtime. In production you'd set it to your actual domain (e.g., `https://mininetflix.yourname.dev`). The CORS config on S3 (`cors-config.json`) controls which origins can PUT directly. The backend CORS config (`SecurityConfig.corsConfigurationSource()`) controls API origins. Both must be updated for production domains.

---

## SERVICE 3 — Amazon SQS (Simple Queue Service)

### What it does here
Decouples the upload confirmation from the MediaConvert job creation. After a user confirms their upload, the backend sends a message to the FIFO queue instead of directly calling MediaConvert synchronously.

```
Queue name:   mininetflix-jobs.fifo
DLQ name:     mininetflix-jobs-dlq.fifo
Type:         FIFO (First-In-First-Out)
Deduplication: Content-based (messageDeduplicationId = videoId)
Visibility timeout: 300s (5 minutes — how long a consumer has to process)
Max receive count: 3 (retried 3× before moved to DLQ)
DLQ retention: 14 days
```

### Message format sent to SQS (from `SqsService.java`)

```json
{
  "videoId": "550e8400-e29b-41d4-a716-446655440000",
  "s3Key": "uploads/user123/550e.../video.mp4",
  "userId": "user123",
  "inputBucket": "mininetflix-input-654654527604",
  "timestamp": 1708766400000
}
```

### Key CLI Commands

```powershell
# View messages in the DLQ (see failed jobs)
aws sqs receive-message `
  --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo `
  --region ap-south-1 `
  --max-number-of-messages 10

# Check how many messages are in the main queue
aws sqs get-queue-attributes `
  --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs.fifo `
  --attribute-names ApproximateNumberOfMessages `
  --region ap-south-1

# Check DLQ depth (failed jobs)
aws sqs get-queue-attributes `
  --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo `
  --attribute-names ApproximateNumberOfMessages `
  --region ap-south-1

# Manually send a test message to the queue (for debugging)
aws sqs send-message `
  --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs.fifo `
  --message-body '{"videoId":"test-123","s3Key":"test/video.mp4"}' `
  --message-group-id "video-processing" `
  --message-deduplication-id "test-123" `
  --region ap-south-1

# Purge all messages from DLQ (after investigating failures)
aws sqs purge-queue `
  --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo `
  --region ap-south-1
```

### 🔥 Roast Questions — SQS

**Q: Why FIFO and not Standard SQS?**
> Standard SQS can deliver messages out of order and **at-least-once** (could deliver the same message twice). For video processing, duplicate delivery means creating two MediaConvert jobs for the same video — you'd be charged twice and get duplicate output files. FIFO with `messageDeduplicationId = videoId` guarantees exactly-once delivery within a 5-minute dedup window. Order also matters — FIFO ensures the first upload request is processed first.

**Q: You send to SQS but then immediately call MediaConvert from the same backend. Why bother with SQS at all?**
> SQS gives you three things even in this hybrid approach:
> 1. **DLQ safety net**: If the MediaConvert call fails, the SQS message remains unacknowledged and gets retried up to 3 times before landing in the DLQ — nothing is silently lost.
> 2. **Decoupling path**: The SQS message can later be consumed by a Lambda instead of the backend — zero API change required.
> 3. **Audit trail**: DLQ messages are retained for 14 days — you can see exactly which videos failed and why.

**Q: What is the visibility timeout and why is it 300 seconds?**
> When a consumer reads a message, SQS hides it from other consumers for the visibility timeout period. If the consumer doesn't delete the message within that time (because it crashed or took too long), SQS makes the message visible again for retry. 300 seconds = 5 minutes. MediaConvert job creation + status check should complete well within 5 minutes. If not, the job would be retried — but idempotency (`messageDeduplicationId`) prevents duplicate MediaConvert jobs.

**Q: What happens to a video message when MediaConvert fails 3 times?**
> The SQS `RedrivePolicy` moves it to the DLQ (`mininetflix-jobs-dlq.fifo`) after `maxReceiveCount = 3` attempted deliveries. The message stays there for 14 days. A CloudWatch Alarm (configured in the CloudFormation stack) fires when the DLQ depth ≥ 1 — alerting you about the failure. You can then inspect the DLQ message, fix the issue, and manually move it back to the main queue.

---

## SERVICE 4 — AWS MediaConvert

### What it does here
Professional-grade video transcoding service. Takes your raw uploaded MP4, transcodes it into HLS (HTTP Live Streaming) format at multiple resolutions, and writes the output to S3.

### MediaConvert Endpoint

```powershell
# Get your account-specific MediaConvert endpoint
aws mediaconvert describe-endpoints --region ap-south-1

# Get just the URL
aws mediaconvert describe-endpoints `
  --region ap-south-1 `
  --query "Endpoints[0].Url" `
  --output text

# Output: https://mediaconvert.ap-south-1.amazonaws.com
```

> ⚠️ MediaConvert has **account-specific endpoints**. You must call `describe-endpoints` once and use the returned URL — it's different for every AWS account.

### What MediaConvert Outputs (for a 1080p input)

```
s3://mininetflix-output-654654527604/processed/{videoId}/
├── video_2160p.m3u8          ← master playlist (confusingly named by MediaConvert)
├── video_2160p_1080p.m3u8   ← 1080p variant playlist
├── video_2160p_720p.m3u8    ← 720p variant playlist
├── video_2160p_480p.m3u8    ← 480p variant playlist
├── video_2160p_0_00001.ts   ← 1080p segment 1
├── video_2160p_0_00002.ts   ← 1080p segment 2
├── video_2160p_1_00001.ts   ← 720p segment 1
└── ...                       ← ~6-second segments per resolution
```

### Key CLI Commands

```powershell
# List all MediaConvert jobs (see current and past)
aws mediaconvert list-jobs `
  --endpoint-url https://mediaconvert.ap-south-1.amazonaws.com `
  --region ap-south-1 `
  --output table

# Get status of a specific job
aws mediaconvert get-job `
  --id {jobId} `
  --endpoint-url https://mediaconvert.ap-south-1.amazonaws.com `
  --region ap-south-1 `
  --query "Job.Status"

# List only FAILED jobs
aws mediaconvert list-jobs `
  --endpoint-url https://mediaconvert.ap-south-1.amazonaws.com `
  --region ap-south-1 `
  --status ERROR `
  --query "Jobs[].{Id:Id,Status:Status,ErrorMessage:ErrorMessage}"
```

### Smart Encoding Logic (from `MediaConvertService.java`)

```java
// Only generate resolutions ≤ input resolution
private List<Output> buildResolutionOutputs(String inputResolution) {
    List<Output> outputs = new ArrayList<>();

    if (is1080pOrHigher(inputResolution)) {
        outputs.add(buildVideoOutput("_1080p", 1920, 1080, 5_000_000, "_1080p"));
    }
    if (is720pOrHigher(inputResolution)) {
        outputs.add(buildVideoOutput("_720p", 1280, 720, 2_500_000, "_720p"));
    }
    // 480p is ALWAYS generated
    outputs.add(buildVideoOutput("_480p", 854, 480, 1_000_000, "_480p"));

    return outputs;
}
```

> **Why this matters**: MediaConvert charges per minute of transcoded output. Generating 1080p from a 480p source costs 3× more and produces zero quality improvement. Smart encoding = direct cost savings.

### IAM Role for MediaConvert

```json
{
  "AssumeRolePolicyDocument": {
    "Statement": [{
      "Effect": "Allow",
      "Principal": { "Service": "mediaconvert.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }]
  },
  "Policies": [{
    "Actions": ["s3:GetObject"],   ← Read from Input Bucket ONLY
    "Resource": "arn:aws:s3:::mininetflix-input-*/*"
  }, {
    "Actions": ["s3:PutObject"],   ← Write to Output Bucket ONLY
    "Resource": "arn:aws:s3:::mininetflix-output-*/*"
  }]
}
```

### 🔥 Roast Questions — MediaConvert

**Q: Why not use FFmpeg on your backend server instead of MediaConvert?**
> FFmpeg on a server: video transcoding is CPU-intensive and takes minutes. A 1GB video at 1080p can take 5-10 minutes on a standard EC2 instance. During that time, the server can't handle other requests. MediaConvert is a **managed, serverless transcoding service** that runs on dedicated hardware — it's faster, you're not paying for idle server time, and you can run thousands of concurrent jobs. Netflix processes millions of videos — that's only feasible with dedicated transcoding infrastructure.

**Q: Why does MediaConvert need its own IAM role instead of using your application's credentials?**
> Principle of least privilege. MediaConvert only needs to read from the input bucket and write to the output bucket — nothing else. If you used your application credentials, MediaConvert would inherit all permissions your app has (DB access, SQS, etc.). The dedicated role means even if MediaConvert is compromised, the blast radius is limited to those two S3 buckets.

**Q: Your code checks input resolution before choosing output resolutions. How do you get the input resolution before MediaConvert runs?**
> You don't — not automatically. The `inputResolution` field on the `Video` entity is populated after the video upload, ideally from metadata the frontend sends or by probing the file. In this implementation, if `inputResolution` is null or empty, the code defaults to generating all resolutions (480p minimum, up to 1080p). A more sophisticated implementation would use MediaConvert's probe job or the frontend to send resolution metadata with the upload confirmation.

**Q: What's the difference between a MediaConvert output `Group` and `Output`?**
> An **Output Group** defines the container/delivery mechanism (HLS, DASH, MP4, etc.) and output destination. An **Output** within that group defines a specific rendition (1080p, 720p, etc.) with its own codec settings. Your CloudFormation creates one `HLS_GROUP` Output Group with multiple Outputs inside — one per resolution. The HLS Output Group generates the master playlist that ties all variant playlists together.

---

## SERVICE 5 — Amazon CloudFront

### What it does here
Global CDN that serves the HLS video output from S3 to users worldwide with low latency. Also enforces access control via RSA-signed URLs — only authenticated users with valid signed URLs can access video content.

### CloudFront Distribution Details

```
Distribution ID: E13J0UL74R5HGC  (from invalidation commands used)
Domain: d1u0dn8mhvuadb.cloudfront.net
Origin: S3 Output bucket (mininetflix-output-654654527604)
Protocol: HTTPS only (redirect-to-https)
HTTP Version: HTTP/2
Price Class: PriceClass_200 (US, Europe, Asia, Africa — excludes only expensive regions)
```

### Cache Behaviors

| Path Pattern | TTL | Reasoning |
|-------------|-----|-----------|
| `*.m3u8` (HLS playlists) | 0s (no cache) | Playlists are dynamic — must be fresh |
| `*` (`.ts` video segments) | Cache-optimized (default) | Segments are immutable — safe to cache forever |

### Key CLI Commands

```powershell
# Invalidate cache (force CloudFront to re-fetch from S3)
aws cloudfront create-invalidation `
  --distribution-id E13J0UL74R5HGC `
  --paths "/*" `
  --region ap-south-1

# Invalidate a specific video only
aws cloudfront create-invalidation `
  --distribution-id E13J0UL74R5HGC `
  --paths "/processed/{videoId}/*"

# Check invalidation status
aws cloudfront list-invalidations `
  --distribution-id E13J0UL74R5HGC

# Get CloudFront distribution config (full JSON)
aws cloudfront get-distribution-config `
  --id E13J0UL74R5HGC `
  --output json
```

### CloudFront Signed URL — Full Setup Flow

```
Step 1: Generate RSA key pair (OpenSSL — locally, ONCE)
Step 2: Upload public key to CloudFront Key Management
Step 3: Create a Key Group containing the public key
Step 4: Attach Key Group to CloudFront distribution cache behavior
Step 5: Backend uses private key to sign URLs at request time
Step 6: CloudFront verifies signature using registered public key
```

**Step 1 — Generate keys (run once):**
```powershell
# Generate RSA 2048-bit private key
openssl genrsa -out private_key.pem 2048

# Extract public key from private key
openssl rsa -pubout -in private_key.pem -out public_key.pem
```

**Step 2 — Upload public key to CloudFront:**
```powershell
# Via CLI (create public key config first in public-key-config.json)
aws cloudfront create-public-key `
  --public-key-config file://public-key-config.json `
  --region ap-south-1

# List existing public keys (get the ID)
aws cloudfront list-public-keys
```

**Step 3 — Create Key Group:**
```powershell
aws cloudfront create-key-group `
  --key-group-config file://key-group-config.json
```

**Step 4 — Update distribution to use Key Group:**
```powershell
# Get current distribution config (need ETag for update)
aws cloudfront get-distribution-config --id E13J0UL74R5HGC

# Update distribution to add TrustedKeyGroups to the cache behavior
aws cloudfront update-distribution `
  --id E13J0UL74R5HGC `
  --distribution-config file://cf-update.json `
  --if-match {ETag}
```

### Exactly How signUrl() Works (Full Explanation)

```java
public String signUrl(String masterPlaylistUrl) {
    // masterPlaylistUrl = "https://d1u0dn8mhvuadb.cloudfront.net/processed/UUID/video.m3u8"

    // 1. Make a WILDCARD resource covering all files under this video
    // resourcePath = "https://d1u0dn8mhvuadb.cloudfront.net/processed/UUID/*"
    String resourcePath = masterPlaylistUrl
        .substring(0, masterPlaylistUrl.lastIndexOf('/') + 1) + "*";

    // 2. Expiry: 6 hours from now
    Instant expirationDate = Instant.now().plus(6, ChronoUnit.HOURS);

    // 3. Build and sign using AWS SDK CloudFrontUtilities
    CloudFrontUtilities cf = CloudFrontUtilities.create();
    CustomSignerRequest req = CustomSignerRequest.builder()
        .resourceUrl(resourcePath)           // wildcard — covers ALL segments
        .privateKey(loadPrivateKey(path))    // RSA-2048 private key from .pem
        .keyPairId(cloudFrontKeyPairId)      // CloudFront Public Key ID (e.g., KABCDEF123)
        .expirationDate(expirationDate)      // validity window
        .build();

    // What AWS SDK does internally:
    // 1. Creates a JSON policy: {"Statement":[{"Resource":resourceUrl,"Condition":{"DateLessThan":{"AWS:EpochTime":expiry}}}]}
    // 2. Base64-encodes it → Policy param
    // 3. RSA-SHA1 signs the Base64 policy with your private key → Signature param
    // 4. Returns all three: Policy, Signature, Key-Pair-Id

    SignedUrl signedUrl = cf.getSignedUrlWithCustomPolicy(req);

    // 5. Extract the query params (Policy=...&Signature=...&Key-Pair-Id=...)
    String signatureQuery = signedUrl.url().substring(signedUrl.url().indexOf('?') + 1);

    // 6. Append to the ACTUAL master playlist URL (not the wildcard resource)
    return masterPlaylistUrl + "?" + signatureQuery;
    // Result: "https://d1u0dn8mhvuadb.cloudfront.net/processed/UUID/video.m3u8?Policy=ey...&Signature=ABC...&Key-Pair-Id=KABC"
}
```

**The resulting signed URL looks like:**
```
https://d1u0dn8mhvuadb.cloudfront.net/processed/UUID/video.m3u8
  ?Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR...   (Base64 JSON policy)
  &Signature=ABC123xyz456...                              (RSA-SHA1 of policy)
  &Key-Pair-Id=K1ABCDEFG123456                           (which public key to verify with)
```

**CloudFront then validates:**
1. Parse `Key-Pair-Id` → look up registered public key
2. Verify `Signature` against `Policy` using that public key (RSA verify)
3. Decode `Policy`, check `Resource` matches the request URL pattern, check `DateLessThan` not expired
4. If all pass → 200 OK, serve content. If any fail → 403 Forbidden.

### 🔥 Roast Questions — CloudFront

**Q: Why Custom Policy signed URLs instead of Canned Policy?**
> Canned Policy signs a single exact URL — one signature for one file. A video has hundreds of files: `master.m3u8`, `720p.m3u8`, `480p.m3u8`, `segment_001.ts`, `segment_002.ts`... You can't sign each segment individually before streaming.
>
> Custom Policy allows a **wildcard resource**: `processed/{UUID}/*`. One signature covers the entire video directory. HLS.js fetches each segment, and the same signature (propagated via `SignedUrlLoader`) is valid for all of them.

**Q: Why did you get 403 on video segments even though the master playlist worked?**
> Real problem from the project. HLS.js loaded the signed master URL fine. But it then fetched variant playlists and `.ts` segments using **relative paths** from the playlist content — those relative paths don't carry the `?Policy=...` query params. CloudFront saw unsigned requests → 403.
>
> Fix: Custom `SignedUrlLoader` in `PlayerPage.jsx`. It extends HLS.js's default loader and intercepts every request before it's sent. If `Policy=` is not already in the URL, it appends the query params from the master playlist URL.

**Q: Why is the HLS playlist cache TTL set to 0 but segments are cache-optimized?**
> HLS manifests reference specific segment filenames. If CloudFront caches an old manifest, the client tries to fetch segments that don't exist yet. So manifests must always be fresh (TTL=0).
>
> Segments (`.ts` files), once written, never change — they're immutable. CloudFront can cache them indefinitely. This is why the cache behavior has separate rules for `*.m3u8` vs everything else.

**Q: OAC vs OAI — which did you use and what's the difference?**
> The CloudFormation template specifies `OriginAccessIdentity: ''` (OAI, older). In practice we moved to **OAC (Origin Access Control)** — the newer recommended approach. The difference:
> - **OAI**: CloudFront uses a special "CloudFront Identity" — S3 bucket policy grants access to it. Limited: doesn't support SSE-KMS, SigV4 signing.
> - **OAC**: CloudFront signs requests to S3 using SigV4 (same signature as AWS SDK). It uses your AWS account + CloudFront service principal. Supports all S3 features including KMS encryption. AWS now recommends OAC over OAI.

---

## SERVICE 6 — AWS IAM

### What it does here

| IAM Entity | Used For |
|-----------|----------|
| `mininetflix-mediaconvert-role` | MediaConvert reads from input S3, writes to output S3 |
| Your IAM user | Backend AWS SDK calls (S3, SQS, MediaConvert) |

### Key CLI Commands

```powershell
# Who am I? (get current identity — most useful command)
aws sts get-caller-identity --output json
# Returns: { "UserId": "...", "Account": "654654527604", "Arn": "arn:aws:iam::654654527604:user/yourname" }

# Get just account ID
$accountId = aws sts get-caller-identity --query Account --output text

# Get just your username from the ARN
$arn = aws sts get-caller-identity --query Arn --output text
$arn.Split('/')[-1]

# List all IAM users
aws iam list-users --query "Users[].UserName" --output table

# Check what policies your user has
aws iam list-attached-user-policies --user-name YOUR_USERNAME

# List MediaConvert role's policies
aws iam list-role-policies --role-name mininetflix-mediaconvert-role

# Get specific policy document
aws iam get-role-policy --role-name mininetflix-mediaconvert-role --policy-name MediaConvertS3Access
```

### 🔥 Roast Questions — IAM

**Q: Why does MediaConvert need its own IAM role? Can't it just use your access key?**
> IAM roles for services work via **STS AssumeRole** — MediaConvert assumes the role temporarily when executing a job. Your application credentials are not used. The role is created with a trust policy: `"Principal": {"Service": "mediaconvert.amazonaws.com"}`. This is safer because credentials are temporary (auto-rotated by STS), scoped to what MediaConvert needs only, and never leave AWS infrastructure.

**Q: What permissions does the MediaConvert role have and why is it minimal?**
> Exactly:
> - `s3:GetObject` on `mininetflix-input-*/*` — read the uploaded video
> - `s3:PutObject` on `mininetflix-output-*/*` — write the processed output
>
> Nothing else. No `s3:DeleteObject`, no access to other buckets, no SQS, no IAM. This is least-privilege: even if someone compromises the MediaConvert service, they can only read/write those specific S3 paths.

---

## SERVICE 7 — Amazon CloudWatch

### What it does here

| Resource | Type | Trigger |
|----------|------|---------|
| `mininetflix-dlq-messages` | Alarm | Fires when DLQ has ≥ 1 message (encoding failure) |
| CloudWatch Events | Event Rule | Fires on MediaConvert job status changes → webhook |

### Key CLI Commands

```powershell
# List all alarms and their state
aws cloudwatch describe-alarms `
  --alarm-names mininetflix-dlq-messages `
  --region ap-south-1

# Get alarm history (when did it fire?)
aws cloudwatch describe-alarm-history `
  --alarm-name mininetflix-dlq-messages `
  --region ap-south-1

# Manually check SQS DLQ depth (same metric the alarm watches)
aws sqs get-queue-attributes `
  --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo `
  --attribute-names ApproximateNumberOfMessages `
  --region ap-south-1
```

### 🔥 Roast Questions — CloudWatch

**Q: The CloudWatch alarm monitors DLQ depth. What actually happens when it fires?**
> As configured in the CloudFormation template, the alarm has `TreatMissingData: notBreaching` and evaluates every 5 minutes. When DLQ has ≥ 1 message, the alarm state goes `OK → ALARM`. In this project no SNS notification is wired up — it's informational. In production you'd attach an SNS topic to the alarm that emails/Slacks your on-call engineer.

**Q: Why is there a polling fallback if CloudWatch handles webhooks?**
> CloudWatch Events to your backend is not guaranteed. It depends on:
> 1. Correct event rule configuration  
> 2. Your backend being publicly reachable  
> 3. The HTTP response being 2xx  
>
> If any of these fail, the video is stuck in `PROCESSING` forever. The `@Scheduled` poll every 2 minutes is the safety net — it queries MediaConvert directly. Eventually consistency: poller catches what webhook missed.

---

## COMMON ISSUES & FIXES (From Your Dev Journal)

### Issue: `'charmap' codec can't decode byte 0x90` during CloudFormation deploy

**Cause**: Windows PowerShell with a non-UTF-8 terminal encoding trying to parse CloudFormation output.

**Fix**: Usually harmless — deployment still succeeds (the emoji/special chars in the template weren't parseable by the terminal codec). The `Successfully created/updated stack - mininetflix` line confirms success.

```powershell
# Set PowerShell to UTF-8 to prevent this
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

---

### Issue: Supabase `UnknownHostException` (IPv6)

**Cause**: Supabase Direct Connection (`db.*.supabase.co:5432`) is IPv6-only. Java prefers IPv4, fails to resolve.

**Fix**: Use the **Supabase Connection Pooler** (IPv4-compatible):
```
Host: aws-1-ap-southeast-1.pooler.supabase.com
Port: 6543
URL: jdbc:postgresql://aws-1-ap-southeast-1.pooler.supabase.com:6543/postgres?prepareThreshold=0
```

> `?prepareThreshold=0` disables server-side prepared statements (Supabase Transaction Pooler doesn't support them).

---

### Issue: CloudFront 403 on video segments

**Cause**: HLS.js fetches `.ts` segment files using relative URLs from the playlist. Those URLs don't carry the signed URL's `?Policy=...&Signature=...` query params.

**Fix**: Custom `SignedUrlLoader` in `PlayerPage.jsx` — extends `Hls.DefaultConfig.loader`, intercepts every load call, appends signature params to each segment URL before the request is sent.

---

### Issue: AWS SDK "Unsupported file type" loading `private_key.pem`

**Cause**: AWS SDK's CloudFront utilities have strict PEM format requirements: they expect `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1 format), but `openssl genrsa` produces PKCS#8 format (`-----BEGIN PRIVATE KEY-----`).

**Fix**: Implemented manual key loading in `S3Service.java`:
```java
private PrivateKey loadPrivateKey(String path) throws Exception {
    String pem = Files.readString(Paths.get(path))
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(pem);
    return KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(decoded));
}
```
Java's `KeyFactory` natively handles PKCS8 format. This completely bypasses the SDK's file-loading logic.

---

## COST MANAGEMENT

### Monthly Cost Estimate (Light Usage)

| Service | Usage | Cost |
|---------|-------|------|
| S3 (10GB stored) | ~$0.23/month | |
| MediaConvert (100 min output) | ~$0.75/month | |
| CloudFront (50GB egress) | ~$4.25/month | |
| SQS (< 1M requests) | Free tier | $0 |
| Redis (Docker local) | $0 | |
| PostgreSQL (Supabase free tier) | $0 | |
| **Total** | | **~$5–20/month** |

### Stop All Charges

```powershell
# 1. Empty S3 (storage costs stop immediately)
$accountId = aws sts get-caller-identity --query Account --output text
aws s3 rm s3://mininetflix-input-$accountId --recursive --region ap-south-1
aws s3 rm s3://mininetflix-output-$accountId --recursive --region ap-south-1

# 2. Delete the stack (stops CloudFront, SQS, IAM, CloudWatch)
aws cloudformation delete-stack --stack-name mininetflix --region ap-south-1

# 3. Stop Redis
docker-compose down

# 4. Verify stack is gone
aws cloudformation describe-stacks --stack-name mininetflix --region ap-south-1
# Should return: "Stack with id mininetflix does not exist"
```

> **CloudFront note**: You cannot "pause" CloudFront. But when idle (no video requests), data transfer cost is ~$0. The only fixed cost is the distribution itself (~$0.01/month) — negligible.

---

## QUICK REFERENCE — ALL CLI COMMANDS SUMMARY

```powershell
# ── IDENTITY ────────────────────────────────────────────────────────────
aws sts get-caller-identity                                         # Who am I?

# ── CLOUDFORMATION ──────────────────────────────────────────────────────
cd .\infrastructure\
aws cloudformation deploy --template-file cloudformation.yml --stack-name mininetflix --capabilities CAPABILITY_NAMED_IAM --region ap-south-1
aws cloudformation describe-stacks --stack-name mininetflix --region ap-south-1 --query "Stacks[0].Outputs" --output table
aws cloudformation describe-stacks --stack-name mininetflix --region ap-south-1 --query "Stacks[0].StackStatus"
aws cloudformation delete-stack --stack-name mininetflix --region ap-south-1
aws cloudformation describe-stack-events --stack-name mininetflix --region ap-south-1

# ── S3 ──────────────────────────────────────────────────────────────────
aws s3 ls s3://mininetflix-output-654654527604/processed/ --region ap-south-1
aws s3 rm s3://mininetflix-input-654654527604 --recursive --region ap-south-1
aws s3 rm s3://mininetflix-output-654654527604 --recursive --region ap-south-1

# ── SQS ─────────────────────────────────────────────────────────────────
aws sqs get-queue-attributes --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo --attribute-names ApproximateNumberOfMessages --region ap-south-1
aws sqs receive-message --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo --region ap-south-1
aws sqs purge-queue --queue-url https://sqs.ap-south-1.amazonaws.com/654654527604/mininetflix-jobs-dlq.fifo --region ap-south-1

# ── MEDIACONVERT ─────────────────────────────────────────────────────────
aws mediaconvert describe-endpoints --region ap-south-1 --query "Endpoints[0].Url" --output text
aws mediaconvert list-jobs --endpoint-url https://mediaconvert.ap-south-1.amazonaws.com --region ap-south-1

# ── CLOUDFRONT ───────────────────────────────────────────────────────────
aws cloudfront create-invalidation --distribution-id E13J0UL74R5HGC --paths "/*"
aws cloudfront list-invalidations --distribution-id E13J0UL74R5HGC
aws cloudfront list-public-keys

# ── KEYS (Run once during initial setup) ─────────────────────────────────
openssl genrsa -out private_key.pem 2048
openssl rsa -pubout -in private_key.pem -out public_key.pem
```
