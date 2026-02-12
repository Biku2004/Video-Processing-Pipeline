import React, { useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useDropzone } from 'react-dropzone'
import { Upload, Film, CheckCircle, AlertCircle, ArrowRight, X, Info } from 'lucide-react'
import { videoApi, uploadToS3 } from '../api/client'
import toast from 'react-hot-toast'

const UPLOAD_STEPS = [
  { id: 1, label: 'Get Upload URL', desc: 'Backend generates a presigned S3 URL' },
  { id: 2, label: 'Upload to S3', desc: 'File goes directly to S3 — no backend bottleneck' },
  { id: 3, label: 'Queue Job', desc: 'Trigger async MediaConvert transcoding pipeline' },
]

export default function UploadPage() {
  const navigate = useNavigate()
  const [file, setFile] = useState(null)
  const [title, setTitle] = useState('')
  const [step, setStep] = useState(0)      // 0=idle, 1=getting URL, 2=uploading, 3=confirming, 4=done
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState(null)

  const onDrop = useCallback((acceptedFiles, rejectedFiles) => {
    if (rejectedFiles.length > 0) {
      const reason = rejectedFiles[0].errors[0]?.message || 'File rejected'
      toast.error(reason)
      return
    }
    if (acceptedFiles[0]) {
      const f = acceptedFiles[0]
      setFile(f)
      setTitle(f.name.replace(/\.[^/.]+$/, '').replace(/[_-]/g, ' '))
      setError(null)
    }
  }, [])

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: { 'video/*': ['.mp4', '.mov', '.avi', '.mkv', '.webm', '.flv', '.wmv', '.m4v'] },
    maxFiles: 1,
    maxSize: 5 * 1024 * 1024 * 1024, // 5GB
    disabled: step > 0,
  })

  const removeFile = (e) => {
    e.stopPropagation()
    setFile(null)
    setTitle('')
    setError(null)
  }

  const formatSize = (bytes) => {
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
  }

  const handleUpload = async () => {
    if (!file) return
    setError(null)

    try {
      // ── Step 1: Get presigned URL from backend ──────────────────────
      // POST /api/videos/upload-url
      // Body: { filename, contentType, fileSize, title }
      setStep(1)
      const { data: urlData } = await videoApi.getUploadUrl(
        file.name,
        file.type || 'video/mp4',
        file.size,
        title || file.name
      )
      // Response: { videoId, uploadUrl, s3Key, expiresInSeconds, userId }
      const { videoId, uploadUrl, userId } = urlData

      // ── Step 2: Upload directly to S3 with presigned URL ─────────────
      // This does NOT go through our backend → scalable!
      // Must include x-amz-meta-* headers that match the presigned URL signature
      setStep(2)
      setProgress(0)
      await uploadToS3(uploadUrl, file, {
        'x-amz-meta-video-id': videoId,
        'x-amz-meta-user-id': userId
      }, (pct) => setProgress(pct))

      // ── Step 3: Confirm upload + trigger processing pipeline ─────────
      // POST /api/videos/{videoId}/confirm
      // Body: { fileSizeBytes }
      setStep(3)
      await videoApi.confirmUpload(videoId, file.size)

      // ── Done! ────────────────────────────────────────────────────────
      setStep(4)
      toast.success('Video uploaded and processing started!')

      // Navigate to dashboard after 2s
      setTimeout(() => navigate('/dashboard'), 2000)

    } catch (err) {
      const msg = err.response?.data?.message || err.message || 'Upload failed'
      setError(msg)
      setStep(0)
      setProgress(0)

      if (err.response?.status === 429) {
        toast.error('Daily upload limit reached. Upgrade to Pro for more uploads.')
      } else {
        toast.error(msg)
      }
    }
  }

  const isUploading = step > 0 && step < 4
  const isDone = step === 4

  return (
    <div className="page">
      <div className="container section">
        <div style={{ maxWidth: 640, margin: '0 auto' }}>

          {/* Header */}
          <div style={{ marginBottom: 32 }}>
            <h1 style={{ fontSize: 32, fontWeight: 800, letterSpacing: '-0.03em' }}>Upload Video</h1>
            <p className="text-secondary text-sm" style={{ marginTop: 6, fontFamily: 'var(--font-mono)' }}>
              File goes directly to S3 via presigned URL — no backend bottleneck
            </p>
          </div>

          {/* Architecture info */}
          <div className="card" style={{ padding: '16px 20px', marginBottom: 24 }}>
            <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
              <Info size={14} color="var(--amber)" style={{ marginTop: 2, flexShrink: 0 }} />
              <div>
                <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--amber)', fontFamily: 'var(--font-mono)', marginBottom: 6 }}>
                  PIPELINE FLOW
                </div>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  {UPLOAD_STEPS.map((s, i) => (
                    <React.Fragment key={s.id}>
                      <div style={{
                        padding: '4px 10px',
                        background: step >= s.id ? 'var(--amber-dim)' : 'var(--bg-elevated)',
                        borderRadius: 4,
                        fontSize: 11,
                        fontFamily: 'var(--font-mono)',
                        color: step >= s.id ? 'var(--amber)' : 'var(--text-muted)',
                        border: `1px solid ${step >= s.id ? 'rgba(212,168,67,0.3)' : 'transparent'}`,
                        transition: 'all var(--transition)',
                      }}>
                        {s.id}. {s.label}
                      </div>
                      {i < UPLOAD_STEPS.length - 1 && (
                        <span style={{ color: 'var(--text-muted)', alignSelf: 'center', fontSize: 12 }}>→</span>
                      )}
                    </React.Fragment>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/* Dropzone */}
          <div {...getRootProps()} className={`dropzone ${isDragActive ? 'active' : ''}`}
            style={{ pointerEvents: isUploading || isDone ? 'none' : 'auto', opacity: isDone ? 0.5 : 1 }}>
            <input {...getInputProps()} />

            {file ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 16, textAlign: 'left' }}>
                <div style={{
                  width: 48, height: 48, background: 'var(--amber-dim)',
                  borderRadius: 10, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  flexShrink: 0,
                }}>
                  <Film size={22} color="var(--amber)" />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 700, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {file.name}
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: 3 }}>
                    {formatSize(file.size)} · {file.type || 'video'}
                  </div>
                </div>
                {!isUploading && (
                  <button className="btn btn-ghost btn-sm" onClick={removeFile} style={{ flexShrink: 0 }}>
                    <X size={14} />
                  </button>
                )}
              </div>
            ) : (
              <div>
                <div style={{
                  width: 56, height: 56, background: 'var(--bg-elevated)',
                  borderRadius: 14, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  margin: '0 auto 16px',
                }}>
                  <Upload size={24} color={isDragActive ? 'var(--amber)' : 'var(--text-muted)'} />
                </div>
                <p style={{ fontWeight: 700, fontSize: 15, color: isDragActive ? 'var(--amber)' : 'var(--text-primary)' }}>
                  {isDragActive ? 'Drop it here!' : 'Drag & drop your video'}
                </p>
                <p className="text-muted text-sm" style={{ marginTop: 6 }}>
                  or click to browse · MP4, MOV, MKV, AVI, WebM supported
                </p>
                <p className="text-muted" style={{ fontSize: 11, marginTop: 8, fontFamily: 'var(--font-mono)' }}>
                  Max 500MB (FREE) · 5GB (PRO)
                </p>
              </div>
            )}
          </div>

          {/* Title Input */}
          {file && (
            <div className="input-group" style={{ marginTop: 16 }}>
              <label className="input-label">Video Title</label>
              <input
                type="text"
                className="input-field"
                value={title}
                onChange={e => setTitle(e.target.value)}
                placeholder="Enter a title for your video"
                disabled={isUploading}
                maxLength={200}
              />
            </div>
          )}

          {/* Progress */}
          {isUploading && (
            <div className="card" style={{ padding: '20px 24px', marginTop: 20 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
                <div className="spinner" />
                <div>
                  <div style={{ fontSize: 14, fontWeight: 700 }}>
                    {step === 1 && 'Getting secure upload URL...'}
                    {step === 2 && `Uploading to S3... ${progress}%`}
                    {step === 3 && 'Starting transcoding pipeline...'}
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: 2 }}>
                    {UPLOAD_STEPS.find(s => s.id === step)?.desc}
                  </div>
                </div>
              </div>
              {step === 2 && (
                <div>
                  <div className="progress-bar">
                    <div className="progress-fill" style={{ width: `${progress}%` }} />
                  </div>
                  <div style={{ textAlign: 'right', fontSize: 11, fontFamily: 'var(--font-mono)', color: 'var(--text-muted)', marginTop: 4 }}>
                    {progress}% · direct S3 upload
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Success */}
          {isDone && (
            <div className="card fade-in" style={{
              padding: '20px 24px',
              marginTop: 20,
              borderColor: 'rgba(76,175,125,0.3)',
              background: 'rgba(76,175,125,0.05)',
            }}>
              <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                <CheckCircle size={20} color="var(--green)" />
                <div>
                  <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--green)' }}>
                    Upload complete!
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: 2 }}>
                    MediaConvert is now encoding your video · Redirecting to dashboard...
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Error */}
          {error && (
            <div className="card" style={{
              padding: '16px 20px',
              marginTop: 16,
              borderColor: 'rgba(224,85,85,0.3)',
              background: 'rgba(224,85,85,0.05)',
            }}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <AlertCircle size={14} color="var(--red)" style={{ marginTop: 2, flexShrink: 0 }} />
                <span style={{ fontSize: 13, color: 'var(--red)', fontFamily: 'var(--font-mono)' }}>{error}</span>
              </div>
            </div>
          )}

          {/* Upload Button */}
          {!isDone && (
            <button
              className="btn btn-primary btn-full btn-lg"
              style={{ marginTop: 24 }}
              disabled={!file || isUploading}
              onClick={handleUpload}
            >
              {isUploading
                ? <><div className="spinner" /> Processing...</>
                : <><Upload size={16} /> Start Upload <ArrowRight size={16} /></>
              }
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
