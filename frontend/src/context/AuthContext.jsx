import React, { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { authApi } from '../api/client'
import toast from 'react-hot-toast'

const AuthContext = createContext(null)

export const useAuth = () => {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  // Restore session from localStorage on mount
  useEffect(() => {
    const token = localStorage.getItem('token')
    const savedUser = localStorage.getItem('user')
    if (token && savedUser) {
      try {
        setUser(JSON.parse(savedUser))
      } catch {
        localStorage.removeItem('token')
        localStorage.removeItem('user')
      }
    }
    setLoading(false)
  }, [])

  const register = useCallback(async (username, email, password) => {
    const { data } = await authApi.register({ username, email, password })
    // Response: { token, userId, username, email, tier, message }
    localStorage.setItem('token', data.token)
    const userData = {
      id: data.userId,
      username: data.username,
      email: data.email,
      tier: data.tier
    }
    localStorage.setItem('user', JSON.stringify(userData))
    setUser(userData)
    toast.success(`Welcome, ${data.username}!`)
    return data
  }, [])

  const login = useCallback(async (email, password) => {
    const { data } = await authApi.login({ email, password })
    // Response: { token, userId, username, email, tier, message }
    localStorage.setItem('token', data.token)
    const userData = {
      id: data.userId,
      username: data.username,
      email: data.email,
      tier: data.tier
    }
    localStorage.setItem('user', JSON.stringify(userData))
    setUser(userData)
    toast.success(`Welcome back, ${data.username}!`)
    return data
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    setUser(null)
    toast('Signed out', { icon: '👋' })
  }, [])

  return (
    <AuthContext.Provider value={{ user, loading, register, login, logout, isAuthenticated: !!user }}>
      {children}
    </AuthContext.Provider>
  )
}
