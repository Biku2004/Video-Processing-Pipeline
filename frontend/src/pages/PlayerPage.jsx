import React, { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import Hls from 'hls.js'
import { ArrowLeft, Wifi, MonitorPlay, Clock, Film, AlertCircle } from 'lucide-react'
import { videoApi } from '../api/client'

export default function PlayerPage() {
  const { videoId } = useParams()
  const navigate = useNavigate()
  const videoRef = useRef(null)
  const hlsRef = useRef(null)

  const [videoMeta, setVideoMeta] = useState(null)
  const [streamInfo, setStreamInfo] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [currentQuality, setCurrentQuality] = useState('auto')
  const [hlsStats, setHlsStats] = useState({ bandwidth: 0, level: 'auto' })

  // ── Fetch video metadata ──────────────────────────────────────────
  useEffect(() => {
    const fetchMeta = async () => {
      try {
        const [{ data: meta }, { data: stream }] = await Promise.all([
          videoApi.getOne(videoId),          // GET /api/videos/{videoId}
          videoApi.getStreamingInfo(videoId) // GET /api/videos/{videoId}/stream
        ])
        setVideoMeta(meta)
        setStreamInfo(stream)
      } catch (err) {
        if (err.response?.status === 409) {
          setError('Video is still being processed. Please check back soon.')
        } else {
          setError(err.response?.data?.message || 'Failed to load video')
        }
      } finally {
        setLoading(false)
      }
    }
    fetchMeta()
  }, [videoId])

  // ── Initialize HLS.js player ──────────────────────────────────────
  useEffect(() => {
    if (!streamInfo?.masterPlaylistUrl || !videoRef.current) return

    const videoEl = videoRef.current
    const src = streamInfo.masterPlaylistUrl

    // Clean up previous instance
    if (hlsRef.current) {
      hlsRef.current.destroy()
    }

    if (Hls.isSupported()) {
      // Custom Loader to propagate Signed URL query params (Policy, Signature) to segments
      // HLS.js doesn't do this by default for relative paths if they are resolved without params
      class SignedUrlLoader extends Hls.DefaultConfig.loader {
        constructor(config) {
          super(config)
          const load = this.load.bind(this)
          this.load = function (context, config, callbacks) {
            // Check if URL needs signing (if it's targeting our CloudFront domain)
            // simplest check: if master playlist has query params, append them to segments
            try {
              const masterUrl = new URL(src)
              const query = masterUrl.search

              if (query && context.url.indexOf('Policy=') === -1) {
                // Determine separator
                const separator = context.url.indexOf('?') === -1 ? '?' : '&'
                // Append query params (remove leading ? from master query)
                context.url += separator + query.substring(1)
              }
            } catch (e) {
              // ignore URL parsing errors
            }

            load(context, config, callbacks)
          }
        }
      }

      // HLS.js: supports Chrome, Firefox, Edge (most browsers)
      const hls = new Hls({
        loader: SignedUrlLoader, // Use our custom loader
        enableWorker: true,
        lowLatencyMode: false,
        // Smart buffering: download in batches based on progress
        maxBufferLength: 10,          // Keep 10s of buffer ahead of playback position
        maxMaxBufferLength: 20,       // Max allowed buffer length (20s)
        maxBufferSize: 60 * 1000 * 1000, // Max 60MB buffer size
        backBufferLength: 30,         // Keep 30s of back buffer for rewinding
        // Adaptive bitrate config
        startLevel: -1,       // auto start quality
        abrEwmaFastLive: 3,   // fast adaptation
        abrEwmaSlowLive: 9,
        capLevelToPlayerSize: true, // don't load 1080p for small player
        debug: false,
      })

      hls.loadSource(src)
      hls.attachMedia(videoEl)

      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        videoEl.play().catch(() => {/* autoplay blocked */ })
      })

      // Track current quality level for display
      hls.on(Hls.Events.LEVEL_SWITCHED, (_, data) => {
        const level = hls.levels[data.level]
        if (level) {
          setHlsStats({
            bandwidth: Math.round(level.bitrate / 1000),
            level: level.height ? `${level.height}p` : 'auto'
          })
        }
      })

      hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            hls.startLoad() // retry network errors
          } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            hls.recoverMediaError()
          } else {
            setError('Playback error. Please refresh.')
            hls.destroy()
          }
        }
      })

      hlsRef.current = hls

    } else if (videoEl.canPlayType('application/vnd.apple.mpegurl')) {
      // Native HLS: Safari
      videoEl.src = src
      videoEl.play().catch(() => { })
    } else {
      setError('Your browser does not support HLS streaming.')
    }

    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy()
        hlsRef.current = null
      }
    }
  }, [streamInfo])

  const formatDuration = (secs) => {
    if (!secs) return '—'
    const h = Math.floor(secs / 3600)
    const m = Math.floor((secs % 3600) / 60)
    const s = secs % 60
    return h > 0
      ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
      : `${m}:${String(s).padStart(2, '0')}`
  }

  if (loading) {
    return (
      <div className="page">
        <div className="container section" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
          <div className="spinner spinner-lg" />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="page">
        <div className="container section" style={{ maxWidth: 600, margin: '0 auto' }}>
          <div className="card" style={{
            padding: '40px 32px', textAlign: 'center',
            borderColor: 'rgba(224,85,85,0.3)',
          }}>
            <AlertCircle size={40} color="var(--red)" style={{ marginBottom: 16 }} />
            <h2 style={{ fontWeight: 800, marginBottom: 8 }}>Playback Error</h2>
            <p className="text-secondary" style={{ fontSize: 14, marginBottom: 24 }}>{error}</p>
            <div style={{ display: 'flex', gap: 12, justifyContent: 'center' }}>
              <Link to="/dashboard" style={{ textDecoration: 'none' }}>
                <button className="btn btn-primary"><ArrowLeft size={14} /> Back to Library</button>
              </Link>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      <div className="container section">
        <div style={{ maxWidth: 900, margin: '0 auto' }}>

          {/* Back button */}
          <button className="btn btn-ghost btn-sm" onClick={() => navigate('/dashboard')}
            style={{ marginBottom: 20 }}>
            <ArrowLeft size={14} />
            Back to Library
          </button>

          {/* Video Player */}
          <div className="video-container" style={{ background: '#000', borderRadius: 'var(--radius-lg)' }}>
            <video
              ref={videoRef}
              controls
              playsInline
              style={{ width: '100%', height: '100%', background: '#000' }}
              poster={streamInfo?.thumbnailUrl}
            />
          </div>

          {/* HLS Stats Bar */}
          {hlsRef.current && (
            <div style={{
              display: 'flex',
              gap: 16,
              marginTop: 12,
              padding: '8px 16px',
              background: 'var(--bg-surface)',
              borderRadius: 'var(--radius-md)',
              border: '1px solid var(--border-subtle)',
            }}>
              <span style={{ fontSize: 11, fontFamily: 'var(--font-mono)', color: 'var(--text-muted)' }}>
                <span style={{ color: 'var(--amber)' }}>●</span> HLS Adaptive Streaming
              </span>
              <span style={{ fontSize: 11, fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                Current: <strong style={{ color: 'var(--text-primary)' }}>{hlsStats.level}</strong>
              </span>
              <span style={{ fontSize: 11, fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                Bitrate: <strong style={{ color: 'var(--text-primary)' }}>{hlsStats.bandwidth} kbps</strong>
              </span>
            </div>
          )}

          {/* Video Info */}
          {videoMeta && (
            <div style={{ marginTop: 24 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16 }}>
                <div style={{ flex: 1 }}>
                  <h1 style={{ fontSize: 24, fontWeight: 800, letterSpacing: '-0.02em' }}>
                    {videoMeta.title}
                  </h1>
                  <div style={{ display: 'flex', gap: 16, marginTop: 8, flexWrap: 'wrap' }}>
                    {videoMeta.durationSeconds && (
                      <span style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 13, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                        <Clock size={12} />
                        {formatDuration(videoMeta.durationSeconds)}
                      </span>
                    )}
                    {videoMeta.originalResolution && (
                      <span style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 13, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                        <MonitorPlay size={12} />
                        {videoMeta.originalResolution}
                      </span>
                    )}
                  </div>
                </div>

                {/* Available qualities */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-end' }}>
                  <span style={{ fontSize: 11, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>Available qualities</span>
                  <div style={{ display: 'flex', gap: 6 }}>
                    {[
                      streamInfo?.has1080p && '1080p',
                      streamInfo?.has720p && '720p',
                      streamInfo?.has480p && '480p',
                    ].filter(Boolean).map(q => (
                      <span key={q} style={{
                        padding: '3px 8px',
                        background: hlsStats.level === q ? 'var(--amber-dim)' : 'var(--bg-elevated)',
                        borderRadius: 4,
                        fontSize: 11,
                        fontFamily: 'var(--font-mono)',
                        color: hlsStats.level === q ? 'var(--amber)' : 'var(--text-secondary)',
                        border: `1px solid ${hlsStats.level === q ? 'rgba(212,168,67,0.3)' : 'var(--border-subtle)'}`,
                        transition: 'all var(--transition)',
                      }}>{q}</span>
                    ))}
                  </div>
                </div>
              </div>

              {/* Technical info */}
              <div className="card" style={{ padding: '16px 20px', marginTop: 20 }}>
                <div style={{ display: 'flex', gap: 10, marginBottom: 10 }}>
                  <Film size={14} color="var(--amber)" />
                  <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--amber)', fontFamily: 'var(--font-mono)' }}>
                    PIPELINE INFO
                  </span>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 12 }}>
                  {[
                    { label: 'Protocol', value: 'HLS (HTTP Live Streaming)' },
                    { label: 'Player', value: Hls.isSupported() ? 'HLS.js' : 'Native Safari' },
                    { label: 'Streaming', value: 'Adaptive Bitrate (ABR)' },
                    { label: 'CDN', value: 'AWS CloudFront' },
                    { label: 'Encoding', value: 'AWS MediaConvert' },
                    { label: 'Format', value: 'H.264 + AAC' },
                  ].map(({ label, value }) => (
                    <div key={label}>
                      <div style={{ fontSize: 10, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
                      <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 }}>{value}</div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
