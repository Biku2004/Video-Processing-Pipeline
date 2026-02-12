import axios from 'axios'
import toast from 'react-hot-toast'

// =====================================================================
// AXIOS INSTANCE — Base configuration
// Uses Vite proxy in dev (no CORS issues), real URL in production
// =====================================================================
const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// Attach JWT token to every request
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Handle auth errors globally
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

// =====================================================================
// AUTH API
// Backend: POST /api/auth/register, POST /api/auth/login
// =====================================================================
export const authApi = {
  /**
   * Register new user
   * Body: { username, email, password }
   * Response: { token, userId, username, email, tier, message }
   */
  register: (data) => api.post('/auth/register', {
    username: data.username,
    email: data.email,
    password: data.password
  }),

  /**
   * Login user
   * Body: { email, password }
   * Response: { token, userId, username, email, tier, message }
   */
  login: (data) => api.post('/auth/login', {
    email: data.email,
    password: data.password
  })
}

// =====================================================================
// VIDEO API
// All endpoints match VideoController exactly
// =====================================================================
export const videoApi = {
  /**
   * GET /api/videos
   * Response: Array of VideoDto
   */
  getAll: () => api.get('/videos'),

  /**
   * GET /api/videos/:videoId
   * Response: VideoDto
   */
  getOne: (videoId) => api.get(`/videos/${videoId}`),

  /**
   * POST /api/videos/upload-url
   * Body: { filename, contentType, fileSize, title }
   * Response: { videoId, uploadUrl, s3Key, expiresInSeconds }
   */
  getUploadUrl: (filename, contentType, fileSize, title) =>
    api.post('/videos/upload-url', {
      filename,
      contentType,
      fileSize,         // Long - must be number, matches backend @NotNull @Positive Long fileSize
      title: title || null
    }),

  /**
   * POST /api/videos/:videoId/confirm
   * Body: { fileSizeBytes } (optional)
   * Response: VideoDto
   * Called AFTER S3 upload completes to trigger processing
   */
  confirmUpload: (videoId, fileSizeBytes) =>
    api.post(`/videos/${videoId}/confirm`, {
      fileSizeBytes: fileSizeBytes || null
    }),

  /**
   * GET /api/videos/:videoId/stream
   * Response: { masterPlaylistUrl, thumbnailUrl, durationSeconds, has1080p, has720p, has480p }
   */
  getStreamingInfo: (videoId) => api.get(`/videos/${videoId}/stream`),

  /**
   * DELETE /api/videos/:videoId
   * Response: 204 No Content
   */
  delete: (videoId) => api.delete(`/videos/${videoId}`),

  /**
   * GET /api/videos/rate-limit
   * Response: { uploadsToday, dailyLimit, remaining, tier, maxFileSizeMb }
   */
  getRateLimitStatus: () => api.get('/videos/rate-limit')
}

// =====================================================================
// S3 DIRECT UPLOAD — Upload file directly to S3 using presigned URL
// This is separate from the api instance - goes directly to S3 (not backend)
// =====================================================================
export const uploadToS3 = async (presignedUrl, file, metadata, onProgress) => {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()

    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && onProgress) {
        const percent = Math.round((event.loaded / event.total) * 100)
        onProgress(percent)
      }
    })

    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
      } else {
        reject(new Error(`S3 upload failed with status ${xhr.status}`))
      }
    })

    xhr.addEventListener('error', () => reject(new Error('S3 upload network error')))
    xhr.addEventListener('abort', () => reject(new Error('S3 upload aborted')))

    xhr.open('PUT', presignedUrl)
    xhr.setRequestHeader('Content-Type', file.type)
    // Must send the same x-amz-meta-* headers that were signed in the presigned URL
    if (metadata) {
      Object.entries(metadata).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value)
      })
    }
    xhr.send(file)
  })
}

export default api
