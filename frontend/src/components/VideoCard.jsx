import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Play, Trash2, Clock, CheckCircle, AlertCircle, Loader, Film } from 'lucide-react'
import { videoApi } from '../api/client'
import { formatDistanceToNow } from 'date-fns'
import toast from 'react-hot-toast'

const STATUS_CONFIG = {
  UPLOADED:   { label: 'Uploaded',   badgeClass: 'badge-gray',  Icon: Clock },
  QUEUED:     { label: 'Queued',     badgeClass: 'badge-blue',  Icon: Clock },
  PROCESSING: { label: 'Processing', badgeClass: 'badge-amber', Icon: Loader },
  READY:      { label: 'Ready',      badgeClass: 'badge-green', Icon: CheckCircle },
  FAILED:     { label: 'Failed',     badgeClass: 'badge-red',   Icon: AlertCircle },
}

export default function VideoCard({ video, onDelete }) {
  const navigate = useNavigate()
  const [deleting, setDeleting] = useState(false)
  const config = STATUS_CONFIG[video.status] || STATUS_CONFIG.UPLOADED
  const { Icon } = config

  const formatSize = (bytes) => {
    if (!bytes) return '—'
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
  }

  const formatDuration = (secs) => {
    if (!secs) return null
    const m = Math.floor(secs / 60), s = secs % 60
    return `${m}:${String(s).padStart(2, '0')}`
  }

  const handleDelete = async (e) => {
    e.stopPropagation()
    if (!window.confirm('Delete this video? This cannot be undone.')) return
    setDeleting(true)
    try {
      await videoApi.delete(video.id)
      toast.success('Video deleted')
      onDelete(video.id)
    } catch {
      toast.error('Failed to delete video')
    } finally {
      setDeleting(false)
    }
  }

  const handlePlay = () => {
    if (video.status === 'READY') {
      navigate(`/watch/${video.id}`)
    } else {
      toast(`Video is ${video.status.toLowerCase()}. Check back soon.`, { icon: '⏳' })
    }
  }

  const qualityBadges = [
    video.has1080p && '1080p',
    video.has720p && '720p',
    video.has480p && '480p',
  ].filter(Boolean)

  return (
    <div
      className="card"
      style={{
        cursor: video.status === 'READY' ? 'pointer' : 'default',
        transition: 'all var(--transition)',
        position: 'relative',
        overflow: 'hidden',
      }}
      onMouseEnter={e => {
        if (video.status === 'READY') {
          e.currentTarget.style.transform = 'translateY(-2px)'
          e.currentTarget.style.borderColor = 'var(--border-default)'
        }
      }}
      onMouseLeave={e => {
        e.currentTarget.style.transform = ''
        e.currentTarget.style.borderColor = ''
      }}
      onClick={handlePlay}
    >
      {/* Thumbnail / Placeholder */}
      <div style={{
        aspectRatio: '16/9',
        background: 'var(--bg-elevated)',
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        overflow: 'hidden',
      }}>
        {video.thumbnailUrl ? (
          <img
            src={video.thumbnailUrl}
            alt={video.title}
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
            onError={e => { e.target.style.display = 'none' }}
          />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
            {video.status === 'PROCESSING' ? (
              <div className="spinner spinner-lg" />
            ) : (
              <Film size={32} color="var(--text-muted)" />
            )}
          </div>
        )}

        {/* Play overlay for ready videos */}
        {video.status === 'READY' && (
          <div style={{
            position: 'absolute', inset: 0,
            background: 'rgba(0,0,0,0.4)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            opacity: 0, transition: 'opacity var(--transition)',
          }}
            onMouseEnter={e => e.currentTarget.style.opacity = '1'}
            onMouseLeave={e => e.currentTarget.style.opacity = '0'}
          >
            <div style={{
              width: 50, height: 50,
              background: 'var(--amber)',
              borderRadius: '50%',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <Play size={20} color="#111" style={{ marginLeft: 2 }} />
            </div>
          </div>
        )}

        {/* Duration badge */}
        {formatDuration(video.durationSeconds) && (
          <div style={{
            position: 'absolute', bottom: 8, right: 8,
            background: 'rgba(0,0,0,0.75)',
            borderRadius: 4,
            padding: '2px 6px',
            fontSize: 11,
            fontFamily: 'var(--font-mono)',
            color: 'var(--text-primary)',
          }}>
            {formatDuration(video.durationSeconds)}
          </div>
        )}

        {/* Processing pulse overlay */}
        {video.status === 'PROCESSING' && (
          <div style={{
            position: 'absolute', bottom: 0, left: 0, right: 0, height: 3,
          }}>
            <div className="progress-fill" style={{
              animation: 'pulse 2s ease-in-out infinite',
              width: '60%',
              margin: 'auto',
            }} />
          </div>
        )}
      </div>

      {/* Info */}
      <div style={{ padding: '14px 16px 16px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
          <h3 style={{
            fontSize: 14, fontWeight: 700,
            color: 'var(--text-primary)',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            flex: 1,
          }}>
            {video.title}
          </h3>
          <button
            className="btn btn-danger btn-sm"
            style={{ padding: '3px 8px', flexShrink: 0 }}
            onClick={handleDelete}
            disabled={deleting}
          >
            {deleting ? <div className="spinner" style={{ width: 12, height: 12 }} /> : <Trash2 size={12} />}
          </button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 10 }}>
          <span className={`badge ${config.badgeClass}`}>
            <Icon size={10} style={{ animation: video.status === 'PROCESSING' ? 'spin 1s linear infinite' : 'none' }} />
            {config.label}
          </span>
          <span style={{ fontSize: 11, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
            {formatSize(video.fileSizeBytes)}
          </span>
        </div>

        {/* Quality variants */}
        {qualityBadges.length > 0 && (
          <div style={{ display: 'flex', gap: 4, marginTop: 8 }}>
            {qualityBadges.map(q => (
              <span key={q} style={{
                padding: '1px 6px',
                background: 'var(--bg-elevated)',
                borderRadius: 4,
                fontSize: 10,
                fontFamily: 'var(--font-mono)',
                color: 'var(--text-secondary)',
                border: '1px solid var(--border-subtle)',
              }}>{q}</span>
            ))}
          </div>
        )}

        {/* Error message */}
        {video.status === 'FAILED' && video.errorMessage && (
          <p style={{ fontSize: 11, color: 'var(--red)', marginTop: 8, fontFamily: 'var(--font-mono)' }}>
            {video.errorMessage}
          </p>
        )}

        {/* Created at */}
        <p style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 8, fontFamily: 'var(--font-mono)' }}>
          {video.createdAt ? formatDistanceToNow(new Date(video.createdAt), { addSuffix: true }) : ''}
        </p>
      </div>
    </div>
  )
}
