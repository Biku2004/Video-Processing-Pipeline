import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Film, Mail, Lock, ArrowRight } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import toast from 'react-hot-toast'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ email: '', password: '' })
  const [loading, setLoading] = useState(false)
  const [errors, setErrors] = useState({})

  const validate = () => {
    const e = {}
    if (!form.email) e.email = 'Email is required'
    else if (!/\S+@\S+\.\S+/.test(form.email)) e.email = 'Invalid email format'
    if (!form.password) e.password = 'Password is required'
    setErrors(e)
    return Object.keys(e).length === 0
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!validate()) return
    setLoading(true)
    try {
      await login(form.email, form.password)
      navigate('/dashboard')
    } catch (err) {
      const msg = err.response?.data?.message || 'Login failed. Please try again.'
      toast.error(msg)
      if (err.response?.status === 401) {
        setErrors({ password: 'Invalid email or password' })
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: 'var(--bg-void)',
      padding: '24px',
    }}>
      {/* Background glow */}
      <div style={{
        position: 'fixed',
        top: '20%', left: '50%', transform: 'translateX(-50%)',
        width: 600, height: 300,
        background: 'radial-gradient(ellipse, rgba(212,168,67,0.07) 0%, transparent 70%)',
        pointerEvents: 'none',
      }} />

      <div className="slide-up" style={{ width: '100%', maxWidth: 420 }}>

        {/* Logo */}
        <div style={{ textAlign: 'center', marginBottom: 40 }}>
          <div style={{
            width: 56, height: 56,
            background: 'var(--amber)',
            borderRadius: 14,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            margin: '0 auto 16px',
          }}>
            <Film size={26} color="#111" strokeWidth={2.5} />
          </div>
          <h1 style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-0.03em' }}>
            Mini<span style={{ color: 'var(--amber)' }}>Flix</span>
          </h1>
          <p className="text-muted text-sm" style={{ marginTop: 6, fontFamily: 'var(--font-mono)' }}>
            Sign in to your account
          </p>
        </div>

        {/* Form */}
        <div className="card" style={{ padding: 32 }}>
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

            <div className="input-group">
              <label className="input-label">Email Address</label>
              <div style={{ position: 'relative' }}>
                <Mail size={14} style={{
                  position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)',
                  color: 'var(--text-muted)', pointerEvents: 'none'
                }} />
                <input
                  type="email"
                  className={`input-field ${errors.email ? 'error' : ''}`}
                  style={{ paddingLeft: 36 }}
                  placeholder="you@example.com"
                  value={form.email}
                  onChange={e => { setForm(p => ({...p, email: e.target.value})); setErrors(p => ({...p, email:''})) }}
                  autoComplete="email"
                />
              </div>
              {errors.email && <span style={{ fontSize: 12, color: 'var(--red)', fontFamily: 'var(--font-mono)' }}>{errors.email}</span>}
            </div>

            <div className="input-group">
              <label className="input-label">Password</label>
              <div style={{ position: 'relative' }}>
                <Lock size={14} style={{
                  position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)',
                  color: 'var(--text-muted)', pointerEvents: 'none'
                }} />
                <input
                  type="password"
                  className={`input-field ${errors.password ? 'error' : ''}`}
                  style={{ paddingLeft: 36 }}
                  placeholder="••••••••"
                  value={form.password}
                  onChange={e => { setForm(p => ({...p, password: e.target.value})); setErrors(p => ({...p, password:''})) }}
                  autoComplete="current-password"
                />
              </div>
              {errors.password && <span style={{ fontSize: 12, color: 'var(--red)', fontFamily: 'var(--font-mono)' }}>{errors.password}</span>}
            </div>

            <button
              type="submit"
              className="btn btn-primary btn-full"
              style={{ marginTop: 4, height: 46 }}
              disabled={loading}
            >
              {loading ? <><div className="spinner" /> Signing in...</> : <>Sign In <ArrowRight size={16} /></>}
            </button>
          </form>

          <p style={{ textAlign: 'center', marginTop: 24, fontSize: 13, color: 'var(--text-muted)' }}>
            Don't have an account?{' '}
            <Link to="/register" style={{ color: 'var(--amber)', textDecoration: 'none', fontWeight: 600 }}>
              Create one
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
