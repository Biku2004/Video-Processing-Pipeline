import React, { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Upload, Film, RefreshCw, Zap, BarChart2 } from 'lucide-react'
import { videoApi } from '../api/client'
import { useAuth } from '../context/AuthContext'
import VideoCard from '../components/VideoCard'
import toast from 'react-hot-toast'

export default function DashboardPage() {
  const { user } = useAuth()
  const [videos, setVideos] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [rateLimitStatus, setRateLimitStatus] = useState(null)

  const fetchVideos = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    else setRefreshing(true)
    try {
      const { data } = await videoApi.getAll()
      setVideos(data)
    } catch {
      if (!silent) toast.error('Failed to load videos')
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  const fetchRateLimitStatus = useCallback(async () => {
    try {
      const { data } = await videoApi.getRateLimitStatus()
      setRateLimitStatus(data)
    } catch {/* silent */}
  }, [])

  useEffect(() => {
    fetchVideos()
    fetchRateLimitStatus()
  }, [fetchVideos, fetchRateLimitStatus])

  // Auto-poll for processing videos every 5 seconds
  useEffect(() => {
    const hasProcessing = videos.some(v => v.status === 'PROCESSING' || v.status === 'QUEUED')
    if (!hasProcessing) return
    const interval = setInterval(() => fetchVideos(true), 5000)
    return () => clearInterval(interval)
  }, [videos, fetchVideos])

  const handleDelete = useCallback((videoId) => {
    setVideos(prev => prev.filter(v => v.id !== videoId))
  }, [])

  const processingCount = videos.filter(v => v.status === 'PROCESSING' || v.status === 'QUEUED').length
  const readyCount = videos.filter(v => v.status === 'READY').length
  const failedCount = videos.filter(v => v.status === 'FAILED').length

  return (
    <div className="page">
      <div className="container section">

        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 32, flexWrap: 'wrap', gap: 16 }}>
          <div>
            <h1 style={{ fontSize: 32, fontWeight: 800, letterSpacing: '-0.03em' }}>
              Video Library
            </h1>
            <p className="text-secondary text-sm" style={{ marginTop: 4, fontFamily: 'var(--font-mono)' }}>
              {user?.username} · {user?.tier} tier
            </p>
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <button
              className="btn btn-ghost btn-sm"
              onClick={() => fetchVideos(true)}
              disabled={refreshing}
            >
              <RefreshCw size={14} style={{ animation: refreshing ? 'spin 0.7s linear infinite' : 'none' }} />
              Refresh
            </button>
            <Link to="/upload" style={{ textDecoration: 'none' }}>
              <button className="btn btn-primary">
                <Upload size={15} />
                Upload Video
              </button>
            </Link>
          </div>
        </div>

        {/* Stats Row */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 14, marginBottom: 32 }}>
          {[
            { label: 'Total Videos', value: videos.length, icon: Film, color: 'var(--text-secondary)' },
            { label: 'Ready to Watch', value: readyCount, icon: BarChart2, color: 'var(--green)' },
            { label: 'Processing', value: processingCount, icon: RefreshCw, color: 'var(--amber)' },
            { label: 'Failed', value: failedCount, icon: Zap, color: failedCount > 0 ? 'var(--red)' : 'var(--text-muted)' },
          ].map(({ label, value, icon: Icon, color }) => (
            <div key={label} className="card" style={{ padding: '16px 20px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div style={{ fontSize: 26, fontWeight: 800, color, lineHeight: 1 }}>{value}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: 4 }}>{label}</div>
                </div>
                <Icon size={16} color={color} />
              </div>
            </div>
          ))}
        </div>

        {/* Rate limit bar */}
        {rateLimitStatus && (
          <div className="card" style={{ padding: '14px 20px', marginBottom: 28, display: 'flex', alignItems: 'center', gap: 16 }}>
            <Zap size={14} color="var(--amber)" />
            <div style={{ flex: 1 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <span style={{ fontSize: 12, color: 'var(--text-secondary)', fontFamily: 'var(--font-mono)' }}>
                  Daily uploads: {rateLimitStatus.uploadsToday}/{rateLimitStatus.dailyLimit}
                </span>
                <span style={{ fontSize: 12, color: 'var(--amber)', fontFamily: 'var(--font-mono)' }}>
                  {rateLimitStatus.remaining} remaining
                </span>
              </div>
              <div className="progress-bar">
                <div className="progress-fill" style={{
                  width: `${(rateLimitStatus.uploadsToday / rateLimitStatus.dailyLimit) * 100}%`,
                  background: rateLimitStatus.remaining === 0
                    ? 'linear-gradient(90deg, var(--red), #ff6b6b)'
                    : undefined,
                }} />
              </div>
            </div>
            <span className="badge badge-amber">{rateLimitStatus.tier}</span>
          </div>
        )}

        {/* Videos Grid */}
        {loading ? (
          <div className="video-grid">
            {[1,2,3,4,5,6].map(i => (
              <div key={i} className="card" style={{ overflow: 'hidden' }}>
                <div className="skeleton" style={{ aspectRatio: '16/9' }} />
                <div style={{ padding: 16 }}>
                  <div className="skeleton" style={{ height: 14, width: '70%', marginBottom: 8 }} />
                  <div className="skeleton" style={{ height: 10, width: '40%' }} />
                </div>
              </div>
            ))}
          </div>
        ) : videos.length === 0 ? (
          <div className="card">
            <div className="empty-state">
              <div className="empty-state-icon">
                <Film size={28} />
              </div>
              <h3 style={{ fontWeight: 700 }}>No videos yet</h3>
              <p className="text-secondary text-sm" style={{ maxWidth: 360, textAlign: 'center' }}>
                Upload your first video and watch it get processed into adaptive HLS streams
              </p>
              <Link to="/upload" style={{ textDecoration: 'none', marginTop: 8 }}>
                <button className="btn btn-primary">
                  <Upload size={15} />
                  Upload First Video
                </button>
              </Link>
            </div>
          </div>
        ) : (
          <div className="video-grid">
            {videos.map(video => (
              <div key={video.id} className="fade-in">
                <VideoCard video={video} onDelete={handleDelete} />
              </div>
            ))}
          </div>
        )}

        {/* Processing notification banner */}
        {processingCount > 0 && (
          <div style={{
            position: 'fixed',
            bottom: 24,
            left: '50%',
            transform: 'translateX(-50%)',
            background: 'var(--bg-elevated)',
            border: '1px solid rgba(212,168,67,0.3)',
            borderRadius: 'var(--radius-xl)',
            padding: '10px 20px',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
            zIndex: 50,
            animation: 'slideUp 300ms ease',
          }}>
            <div className="spinner" />
            <span style={{ fontSize: 13, fontFamily: 'var(--font-mono)' }}>
              {processingCount} video{processingCount > 1 ? 's' : ''} processing
            </span>
            <span className="badge badge-amber">auto-refresh on</span>
          </div>
        )}
      </div>
    </div>
  )
}
