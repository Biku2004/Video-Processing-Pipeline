import React from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Upload, Film, LogOut, Zap } from 'lucide-react'
import { useAuth } from '../context/AuthContext'

export default function Navbar() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const isActive = (path) => location.pathname === path

  return (
    <nav style={{
      position: 'fixed',
      top: 0, left: 0, right: 0,
      height: '64px',
      background: 'rgba(10,10,10,0.85)',
      backdropFilter: 'blur(20px)',
      borderBottom: '1px solid var(--border-subtle)',
      zIndex: 100,
      display: 'flex',
      alignItems: 'center',
    }}>
      <div className="container" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>

        {/* Logo */}
        <Link to="/dashboard" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div style={{
            width: 32, height: 32,
            background: 'var(--amber)',
            borderRadius: '6px',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <Film size={16} color="#111" strokeWidth={2.5} />
          </div>
          <span style={{
            fontFamily: 'var(--font-display)',
            fontWeight: 800,
            fontSize: '18px',
            color: 'var(--text-primary)',
            letterSpacing: '-0.02em',
          }}>
            Mini<span style={{ color: 'var(--amber)' }}>Flix</span>
          </span>
        </Link>

        {/* Nav Links */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
          <Link to="/dashboard" style={{ textDecoration: 'none' }}>
            <button className={`btn btn-ghost btn-sm ${isActive('/dashboard') ? 'active-nav' : ''}`}
              style={isActive('/dashboard') ? { color: 'var(--amber)', borderColor: 'rgba(212,168,67,0.3)' } : {}}>
              <Film size={14} />
              Library
            </button>
          </Link>
          <Link to="/upload" style={{ textDecoration: 'none' }}>
            <button className={`btn btn-ghost btn-sm`}
              style={isActive('/upload') ? { color: 'var(--amber)', borderColor: 'rgba(212,168,67,0.3)' } : {}}>
              <Upload size={14} />
              Upload
            </button>
          </Link>
        </div>

        {/* User */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
              {user?.username}
            </span>
            <span className="badge badge-amber" style={{ padding: '1px 6px', fontSize: '10px', marginTop: '1px' }}>
              <Zap size={8} />
              {user?.tier}
            </span>
          </div>
          <button className="btn btn-ghost btn-sm" onClick={handleLogout} title="Sign out">
            <LogOut size={14} />
          </button>
        </div>
      </div>
    </nav>
  )
}
