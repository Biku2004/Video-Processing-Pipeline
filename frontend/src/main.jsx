import React from 'react'
import ReactDOM from 'react-dom/client'
import { Toaster } from 'react-hot-toast'
import App from './App.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
    <Toaster
      position="bottom-right"
      toastOptions={{
        style: {
          background: '#1a1a1a',
          color: '#f5f0e8',
          border: '1px solid #333',
          fontFamily: 'DM Mono, monospace',
          fontSize: '13px',
        },
        success: { iconTheme: { primary: '#d4a843', secondary: '#1a1a1a' } },
        error: { iconTheme: { primary: '#e05555', secondary: '#1a1a1a' } },
      }}
    />
  </React.StrictMode>
)
