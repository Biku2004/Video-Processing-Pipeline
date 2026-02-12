import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Film, Mail, Lock, User, ArrowRight, CheckCircle } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import toast from 'react-hot-toast'

export default function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', email: '', password: '', confirm: '' })
  const [loading, setLoading] = useState(false)
  const [errors, setErrors] = useState({})

  const validate = () => {
    const e = {}
    if (!form.username) e.username = 'Username is required'
    else if (form.username.length < 3) e.username = 'At least 3 characters'
    else if (!/^[a-zA-Z0-9_]+$/.test(form.username)) e.username = 'Letters, numbers, underscore only'

    if (!form.email) e.email = 'Email is required'
    else if (!/\S+@\S+\.\S+/.test(form.email)) e.email = 'Invalid email'

    if (!form.password) e.password = 'Password is required'
    else if (form.password.length < 8) e.password = 'At least 8 characters'

    if (form.password !== form.confirm) e.confirm = 'Passwords do not match'
    setErrors(e)
    return Object.keys(e).length === 0
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!validate()) return
    setLoading(true)
    try {
      await register(form.username, form.email, form.password)
      navigate('/dashboard')
    } catch (err) {
      const msg = err.response?.data?.message || 'Registration failed'
      toast.error(msg)
      if (msg.toLowerCase().includes('email')) setErrors(p => ({...p, email: msg}))
      if (msg.toLowerCase().includes('username')) setErrors(p => ({...p, username: msg}))
    } finally {
      setLoading(false)
    }
  }

  const renderField = (name, label, type, placeholder, Icon) => (
    <div className="input-group" key={name}>
      <label className="input-label">{label}</label>
      <div style={{ position: 'relative' }}>
        <Icon size={14} style={{
          position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)',
          color: 'var(--text-muted)', pointerEvents: 'none'
        }} />
        <input
          type={type || 'text'}
          className={`input-field ${errors[name] ? 'error' : ''}`}
          style={{ paddingLeft: 36 }}
          placeholder={placeholder}
          value={form[name]}
          onChange={e => { setForm(p => ({...p, [name]: e.target.value})); setErrors(p => ({...p, [name]: ''})) }}
        />
      </div>
      {errors[name] && <span style={{ fontSize: 12, color: 'var(--red)', fontFamily: 'var(--font-mono)' }}>{errors[name]}</span>}
    </div>
  )

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: 'var(--bg-void)',
      padding: '24px',
    }}>
      <div style={{
        position: 'fixed',
        top: '20%', left: '50%', transform: 'translateX(-50%)',
        width: 600, height: 300,
        background: 'radial-gradient(ellipse, rgba(212,168,67,0.07) 0%, transparent 70%)',
        pointerEvents: 'none',
      }} />

      <div className="slide-up" style={{ width: '100%', maxWidth: 420 }}>
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
            Create Account
          </h1>
          <p className="text-muted text-sm" style={{ marginTop: 6, fontFamily: 'var(--font-mono)' }}>
            Start processing your videos
          </p>
        </div>

        {/* Tier info */}
        <div style={{
          padding: '12px 16px',
          background: 'var(--amber-glow)',
          border: '1px solid rgba(212,168,67,0.2)',
          borderRadius: 'var(--radius-md)',
          marginBottom: 20,
          display: 'flex', gap: 10, alignItems: 'flex-start',
        }}>
          <CheckCircle size={14} color="var(--amber)" style={{ marginTop: 2, flexShrink: 0 }} />
          <div>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--amber)', fontFamily: 'var(--font-mono)' }}>FREE TIER</div>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 }}>
              3 uploads/day · 500MB max · 480p–720p encoding
            </div>
          </div>
        </div>

        <div className="card" style={{ padding: 32 }}>
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {renderField('username', 'Username', 'text', 'johndoe', User)}
            {renderField('email', 'Email Address', 'email', 'you@example.com', Mail)}
            {renderField('password', 'Password', 'password', 'min. 8 characters', Lock)}
            {renderField('confirm', 'Confirm Password', 'password', 'repeat password', Lock)}

            <button
              type="submit"
              className="btn btn-primary btn-full"
              style={{ marginTop: 4, height: 46 }}
              disabled={loading}
            >
              {loading ? <><div className="spinner" /> Creating account...</> : <>Create Account <ArrowRight size={16} /></>}
            </button>
          </form>

          <p style={{ textAlign: 'center', marginTop: 24, fontSize: 13, color: 'var(--text-muted)' }}>
            Already have an account?{' '}
            <Link to="/login" style={{ color: 'var(--amber)', textDecoration: 'none', fontWeight: 600 }}>
              Sign in
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
