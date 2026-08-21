import { useState, useEffect, useCallback, useRef } from 'react'
import './index.css'

const STAGE_LABELS = {
  RECEIVED: 'Received',
  JIRA_CREATED: 'Jira Created',
  COLLECTING_CONTEXT: 'Collecting',
  JIRA_CONTEXT_PUBLISHED: 'Context Published',
  AI_INVESTIGATING: 'AI Investigating',
  AI_SKIPPED: 'AI Skipped',
  VALIDATING: 'Validating',
  DRAFT_PR_CREATED: 'PR Created',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
}

const TIMELINE_MARKS = {
  RECEIVED: { text: 'RCV', cls: 'received' },
  JIRA_CREATED: { text: 'JRA', cls: 'jira' },
  COLLECTING_CONTEXT: { text: 'CTX', cls: 'evidence' },
  JIRA_CONTEXT_PUBLISHED: { text: 'PUB', cls: 'jira' },
  AI_INVESTIGATING: { text: 'AI', cls: 'evidence' },
  AI_SKIPPED: { text: 'SKIP', cls: 'skipped' },
  VALIDATING: { text: 'VAL', cls: 'evidence' },
  DRAFT_PR_CREATED: { text: 'PR', cls: 'pr' },
  COMPLETED: { text: 'OK', cls: 'completed' },
  FAILED: { text: 'ERR', cls: 'failed' },
}

function formatTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })
}

function timeAgo(iso) {
  if (!iso) return ''
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  return `${Math.floor(seconds / 86400)}d ago`
}

function StatsCards({ stats }) {
  return (
    <div className="stats-grid">
      <div className="stat-card total">
        <div className="label">Total Incidents</div>
        <div className="value">{stats.total}</div>
      </div>
      <div className="stat-card active">
        <div className="label">Active</div>
        <div className="value">{stats.active}</div>
      </div>
      <div className="stat-card completed">
        <div className="label">Completed</div>
        <div className="value">{stats.completed}</div>
      </div>
      <div className="stat-card failed">
        <div className="label">Failed</div>
        <div className="value">{stats.failed}</div>
      </div>
    </div>
  )
}


function ErrorRateCell({ signalFxExports }) {
  if (!signalFxExports) return <span style={{ color: 'var(--text-muted)' }}>-</span>

  const before = signalFxExports.errorRateBefore ?? 0
  const during = signalFxExports.errorRateDuring ?? 0
  const max = Math.max(before, during, 1)

  return (
    <div className="error-rate-cell">
      <div className="error-bar-container">
        <div className="error-bar before" style={{ height: `${(before / max) * 20}px` }} />
        <div className="error-bar during" style={{ height: `${(during / max) * 20}px` }} />
      </div>
      <div className="error-labels">
        {before.toFixed(1)} / {during.toFixed(1)}
      </div>
    </div>
  )
}

// Every effect is built from plain colored shapes (dots, ribbons, sparks) in shades of blue and
// white — no emoji glyphs — so the row-hover celebration/explosion effects can stay without
// pulling a second color or any pictographs into the UI.
const EFFECT_COLORS = ['var(--blue)', 'var(--blue-strong)', 'var(--blue-border)', '#ffffff']

const STATUS_EFFECT_MAP = {
  FAILED: 'explosion',
  COMPLETED: 'celebration',
  RECEIVED: 'received',
  JIRA_CREATED: 'jira',
  COLLECTING_CONTEXT: 'collecting',
  JIRA_CONTEXT_PUBLISHED: 'jira',
  AI_INVESTIGATING: 'ai',
  AI_SKIPPED: 'skipped',
  VALIDATING: 'ai',
  DRAFT_PR_CREATED: 'pr',
}

function ScreenEffects({ type, origin }) {
  const [particles, setParticles] = useState([])
  const [confetti, setConfetti] = useState([])
  const [fireworks, setFireworks] = useState([])
  const [flash, setFlash] = useState(null)
  const [shaking, setShaking] = useState(false)
  const hasFired = useRef(false)

  useEffect(() => {
    if (type && !hasFired.current) {
      hasFired.current = true

      if (type === 'explosion') triggerExplosion()
      else if (type === 'celebration') triggerCelebration()
      else if (type === 'received') triggerReceived()
      else if (type === 'jira') triggerJira()
      else if (type === 'collecting') triggerCollecting()
      else if (type === 'ai') triggerAI()
      else if (type === 'pr') triggerPR()
      else if (type === 'skipped') triggerSkipped()
    }
    if (!type) {
      hasFired.current = false
    }
  }, [type, origin])

  function doFlash(variant = 'blue', duration = 500) {
    setFlash(variant)
    setTimeout(() => setFlash(null), duration)
  }

  function spawnParticles(centers, { count = 10, spread = 500, sizeMin = 6, sizeMax = 12, dur = 2500 } = {}) {
    const all = []
    centers.forEach((center, ci) => {
      for (let i = 0; i < count; i++) {
        all.push({
          id: Date.now() + ci * 100 + i,
          color: EFFECT_COLORS[(ci * 3 + i) % EFFECT_COLORS.length],
          x: center.x + (Math.random() - 0.5) * 40,
          y: center.y + (Math.random() - 0.5) * 20,
          tx: (Math.random() - 0.5) * spread,
          ty: (Math.random() - 0.5) * spread - 60,
          rot: (Math.random() - 0.5) * 900,
          size: sizeMin + Math.random() * (sizeMax - sizeMin),
          delay: ci * 100 + Math.random() * 150,
          duration: 800 + Math.random() * 600,
        })
      }
    })
    setParticles(all)
    setTimeout(() => setParticles([]), dur)
  }

  function spawnConfetti(count = 50) {
    const vw = window.innerWidth
    const pieces = []
    for (let i = 0; i < count; i++) {
      pieces.push({
        id: Date.now() + i,
        color: EFFECT_COLORS[i % EFFECT_COLORS.length],
        x: Math.random() * vw,
        w: 8 + Math.random() * 8,
        h: 14 + Math.random() * 10,
        sway: (Math.random() - 0.5) * 200,
        spin: (Math.random() - 0.5) * 1080,
        delay: Math.random() * 800,
        fallDuration: 2500 + Math.random() * 2000,
      })
    }
    setConfetti(pieces)
    setTimeout(() => setConfetti([]), 5000)
  }

  function spawnFireworks(count = 3) {
    const vw = window.innerWidth
    const all = []
    for (let ci = 0; ci < count; ci++) {
      const cx = vw * (0.2 + ci * 0.3)
      const cy = 80 + Math.random() * 80
      for (let i = 0; i < 14; i++) {
        const angle = (i / 14) * Math.PI * 2
        const dist = 60 + Math.random() * 80
        all.push({
          id: Date.now() + 1000 + ci * 100 + i,
          x: cx, y: cy,
          fx: Math.cos(angle) * dist,
          fy: Math.sin(angle) * dist,
          color: EFFECT_COLORS[(ci * 5 + i) % EFFECT_COLORS.length],
          delay: ci * 300 + Math.random() * 100,
          duration: 800 + Math.random() * 400,
        })
      }
    }
    setFireworks(all)
    setTimeout(() => setFireworks([]), 3000)
  }

  function screenCenters() {
    const vw = window.innerWidth, vh = window.innerHeight
    return [
      origin,
      { x: vw * 0.15, y: vh * 0.25 },
      { x: vw * 0.85, y: vh * 0.2 },
      { x: vw * 0.5, y: vh * 0.1 },
      { x: vw * 0.3, y: vh * 0.75 },
      { x: vw * 0.75, y: vh * 0.7 },
    ]
  }

  // FAILED — full-screen burst + screen shake
  function triggerExplosion() {
    doFlash('strong')
    setShaking(true)
    setTimeout(() => setShaking(false), 500)
    spawnParticles(screenCenters(), { count: 12, spread: 600, sizeMax: 16 })
  }

  // COMPLETED — confetti rain + fireworks
  function triggerCelebration() {
    doFlash('blue')
    spawnConfetti(60)
    spawnFireworks(3)
  }

  // RECEIVED — sparks radiating from center
  function triggerReceived() {
    doFlash('blue')
    const vw = window.innerWidth, vh = window.innerHeight
    const centers = [
      origin,
      { x: vw * 0.5, y: vh * 0.1 },
      { x: vw * 0.2, y: vh * 0.5 },
      { x: vw * 0.8, y: vh * 0.4 },
    ]
    spawnParticles(centers, { count: 8, spread: 450, sizeMin: 6, sizeMax: 14 })
  }

  // JIRA_CREATED / JIRA_CONTEXT_PUBLISHED — ribbons + sparks
  function triggerJira() {
    doFlash('blue')
    spawnConfetti(40)
    const vw = window.innerWidth, vh = window.innerHeight
    spawnParticles([
      origin,
      { x: vw * 0.3, y: vh * 0.2 },
      { x: vw * 0.7, y: vh * 0.3 },
    ], { count: 8, spread: 400, sizeMin: 6, sizeMax: 12 })
  }

  // COLLECTING_CONTEXT — scanning sparks
  function triggerCollecting() {
    doFlash('blue')
    const vw = window.innerWidth, vh = window.innerHeight
    const scanPoints = []
    for (let i = 0; i < 5; i++) {
      scanPoints.push({ x: vw * (0.1 + i * 0.2), y: vh * (0.2 + Math.random() * 0.5) })
    }
    spawnParticles([origin, ...scanPoints], { count: 6, spread: 350, sizeMin: 6, sizeMax: 12 })
  }

  // AI_INVESTIGATING / VALIDATING — sparks + fireworks
  function triggerAI() {
    doFlash('blue')
    const vw = window.innerWidth, vh = window.innerHeight
    const centers = [
      origin,
      { x: vw * 0.5, y: vh * 0.15 },
      { x: vw * 0.2, y: vh * 0.4 },
      { x: vw * 0.8, y: vh * 0.35 },
      { x: vw * 0.4, y: vh * 0.7 },
    ]
    spawnParticles(centers, { count: 8, spread: 400, sizeMin: 6, sizeMax: 14 })
    spawnFireworks(2)
  }

  // DRAFT_PR_CREATED — ribbons + sparks
  function triggerPR() {
    doFlash('blue')
    spawnConfetti(45)
    const vw = window.innerWidth, vh = window.innerHeight
    spawnParticles([
      origin,
      { x: vw * 0.25, y: vh * 0.3 },
      { x: vw * 0.75, y: vh * 0.25 },
      { x: vw * 0.5, y: vh * 0.6 },
    ], { count: 8, spread: 450, sizeMin: 6, sizeMax: 12 })
  }

  // AI_SKIPPED — a light drift of sparks
  function triggerSkipped() {
    doFlash('blue', 300)
    const vw = window.innerWidth, vh = window.innerHeight
    spawnParticles([
      origin,
      { x: vw * 0.3, y: vh * 0.3 },
      { x: vw * 0.7, y: vh * 0.5 },
    ], { count: 7, spread: 350, sizeMin: 5, sizeMax: 10, dur: 2000 })
  }

  if (!particles.length && !confetti.length && !fireworks.length && !flash) return null

  return (
    <>
      {flash && <div className={`screen-flash ${flash}`} />}
      {shaking && <style>{`.dashboard { animation: screenShake 0.5s ease-out; }`}</style>}
      <div className="effects-container">
        {particles.map(p => (
          <div
            key={p.id}
            className="explosion-particle"
            style={{
              left: p.x,
              top: p.y,
              '--size': `${p.size}px`,
              '--color': p.color,
              '--tx': `${p.tx}px`,
              '--ty': `${p.ty}px`,
              '--rot': `${p.rot}deg`,
              '--delay': `${p.delay}ms`,
              '--duration': `${p.duration}ms`,
            }}
          />
        ))}

        {confetti.map(c => (
          <div
            key={c.id}
            className="confetti-piece"
            style={{
              left: c.x,
              '--sway': `${c.sway}px`,
              '--spin': `${c.spin}deg`,
              '--delay': `${c.delay}ms`,
              '--fall-duration': `${c.fallDuration}ms`,
              '--w': `${c.w}px`,
              '--h': `${c.h}px`,
              '--color': c.color,
            }}
          />
        ))}

        {fireworks.map(f => (
          <div
            key={f.id}
            className="firework-burst"
            style={{
              left: f.x,
              top: f.y,
              '--fx': `${f.fx}px`,
              '--fy': `${f.fy}px`,
              '--color': f.color,
              '--delay': `${f.delay}ms`,
              '--duration': `${f.duration}ms`,
            }}
          />
        ))}
      </div>
    </>
  )
}

function PullRequestLinks({ incident }) {
  const { fixPullRequest, revertPullRequest } = incident
  if (!fixPullRequest && !revertPullRequest) {
    return <span style={{ color: 'var(--text-muted)' }}>-</span>
  }
  return (
    <div className="pr-links">
      {fixPullRequest && (
        <a className="pr-link fix" href={fixPullRequest.url} target="_blank" rel="noreferrer"
           onClick={e => e.stopPropagation()}>
          Fix #{fixPullRequest.number}
        </a>
      )}
      {revertPullRequest && (
        <a className="pr-link revert" href={revertPullRequest.url} target="_blank" rel="noreferrer"
           onClick={e => e.stopPropagation()}>
          Revert #{revertPullRequest.number}
        </a>
      )}
    </div>
  )
}

function IncidentsTable({ incidents, onSelect }) {
  const [effectType, setEffectType] = useState(null)
  const [effectOrigin, setEffectOrigin] = useState({ x: 0, y: 0 })
  const activeRow = useRef(null)

  const handleRowHover = (e, inc) => {
    if (activeRow.current === inc.incidentId) return
    activeRow.current = inc.incidentId

    const rect = e.currentTarget.getBoundingClientRect()
    const origin = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
    setEffectOrigin(origin)

    const effect = STATUS_EFFECT_MAP[inc.status]
    if (effect) setEffectType(effect)
  }

  const handleRowLeave = () => {
    activeRow.current = null
    setEffectType(null)
  }

  const rowClass = (status) => {
    if (status === 'FAILED') return 'failed-row'
    if (status === 'COMPLETED') return 'completed-row'
    return 'effect-row'
  }

  return (
    <div className="table-section">
      <ScreenEffects type={effectType} origin={effectOrigin} />
      <div className="table-header">
        <h2>Incidents</h2>
        <span className="count-badge">{incidents.length}</span>
      </div>
      <div className="table-wrap">
        <table className="incidents-table">
          <thead>
            <tr>
              <th>Incident</th>
              <th>Jira</th>
              <th>Status</th>
              <th>Pull Requests</th>
              <th>Error Rate (Before/During)</th>
              <th>Latency</th>
              <th>Time</th>
            </tr>
          </thead>
          <tbody>
            {incidents.length === 0 && (
              <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 40 }}>No incidents yet</td></tr>
            )}
            {incidents.map(inc => (
              <tr
                key={inc.incidentId}
                className={rowClass(inc.status)}
                onClick={() => onSelect(inc)}
                onMouseEnter={e => handleRowHover(e, inc)}
                onMouseLeave={handleRowLeave}
              >
                <td>
                  <span className="incident-id">{inc.incidentId}</span>
                </td>
                <td>
                  {inc.jiraKey ? (
                    <a className="jira-link" href={inc.jiraUrl} target="_blank" rel="noreferrer"
                       onClick={e => e.stopPropagation()}>
                      {inc.jiraKey}
                    </a>
                  ) : '-'}
                </td>
                <td>
                  <span className={`status-badge ${inc.status}`}>
                    <span className="status-dot" />
                    {STAGE_LABELS[inc.status] || inc.status}
                  </span>
                </td>
                <td><PullRequestLinks incident={inc} /></td>
                <td><ErrorRateCell signalFxExports={inc.signalFxExports} /></td>
                <td>
                  {inc.signalFxExports ? (
                    <span className={`latency-indicator ${inc.signalFxExports.latencyChanged ? 'changed' : 'unchanged'}`}>
                      {inc.signalFxExports.latencyChanged ? 'Changed' : 'Normal'}
                    </span>
                  ) : '-'}
                </td>
                <td style={{ whiteSpace: 'nowrap', color: 'var(--text-muted)', fontSize: 12 }}>
                  {formatTime(inc.timestamp)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Timeline({ incidents }) {
  const items = incidents.slice(0, 20).map(inc => ({
    id: inc.incidentId,
    status: inc.status,
    jiraKey: inc.jiraKey,
    timestamp: inc.timestamp,
    hypothesis: inc.aiOutput?.hypothesis,
  }))

  return (
    <div className="timeline-section">
      <div className="timeline-header">
        <h2>Recent Activity</h2>
      </div>
      <div className="timeline-list">
        {items.length === 0 && <div className="empty-state">No activity</div>}
        {items.map(item => {
          const mark = TIMELINE_MARKS[item.status] || TIMELINE_MARKS.RECEIVED
          return (
            <div className="timeline-item" key={item.id + item.status}>
              <div className={`timeline-icon ${mark.cls}`}>{mark.text}</div>
              <div className="timeline-content">
                <div className="timeline-title">
                  {item.id}{item.jiraKey ? ` • ${item.jiraKey}` : ''}
                </div>
                <div className="timeline-meta">
                  {STAGE_LABELS[item.status] || item.status}
                  {item.hypothesis && ` — ${item.hypothesis.substring(0, 60)}...`}
                  {' • '}{timeAgo(item.timestamp)}
                </div>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function DetailModal({ incident, onClose }) {
  if (!incident) return null

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <h2>
          <span>{incident.incidentId}</span>
          <button className="modal-close" onClick={onClose} aria-label="Close">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
        </h2>

        <div className="detail-grid">
          <span className="detail-label">Status</span>
          <span className="detail-value">
            <span className={`status-badge ${incident.status}`}>
              <span className="status-dot" />
              {STAGE_LABELS[incident.status] || incident.status}
            </span>
          </span>

          <span className="detail-label">Jira</span>
          <span className="detail-value">
            {incident.jiraKey ? (
              <a className="jira-link" href={incident.jiraUrl} target="_blank" rel="noreferrer">
                {incident.jiraKey}
              </a>
            ) : '-'}
          </span>

          <span className="detail-label">Timestamp</span>
          <span className="detail-value">{formatTime(incident.timestamp)}</span>

          <span className="detail-label">Fix PR</span>
          <span className="detail-value">
            {incident.fixPullRequest ? (
              <a className="pr-link fix" href={incident.fixPullRequest.url} target="_blank" rel="noreferrer">
                #{incident.fixPullRequest.number}
              </a>
            ) : '-'}
          </span>

          <span className="detail-label">Revert PR</span>
          <span className="detail-value">
            {incident.revertPullRequest ? (
              <a className="pr-link revert" href={incident.revertPullRequest.url} target="_blank" rel="noreferrer">
                #{incident.revertPullRequest.number}
              </a>
            ) : '-'}
          </span>

          {incident.signalFxExports && (
            <>
              <span className="detail-label">Error Before</span>
              <span className="detail-value">{incident.signalFxExports.errorRateBefore}</span>
              <span className="detail-label">Error During</span>
              <span className="detail-value">{incident.signalFxExports.errorRateDuring}</span>
              <span className="detail-label">Latency</span>
              <span className="detail-value">
                <span className={`latency-indicator ${incident.signalFxExports.latencyChanged ? 'changed' : 'unchanged'}`}>
                  {incident.signalFxExports.latencyChanged ? 'Changed' : 'Normal'}
                </span>
              </span>
              {incident.signalFxExports.dashboardUrl && (
                <>
                  <span className="detail-label">Dashboard</span>
                  <span className="detail-value">
                    <a className="jira-link" href={incident.signalFxExports.dashboardUrl} target="_blank" rel="noreferrer">
                      Open Dashboard
                    </a>
                  </span>
                </>
              )}
            </>
          )}
        </div>

        {incident.splunkLogs && (
          <div className="detail-section">
            <h3>Splunk Logs</h3>
            <div className="detail-grid" style={{ marginBottom: 12 }}>
              <span className="detail-label">Error Count</span>
              <span className="detail-value">{incident.splunkLogs.errorCount}</span>
              <span className="detail-label">Top Error</span>
              <span className="detail-value">{incident.splunkLogs.topError}</span>
            </div>
            {incident.splunkLogs.samples?.length > 0 && (
              <div className="code-block">
                {incident.splunkLogs.samples.join('\n')}
              </div>
            )}
          </div>
        )}

        {incident.aiOutput && (
          <div className="detail-section">
            <h3>AI Analysis</h3>
            <div className="detail-grid" style={{ marginBottom: 12 }}>
              <span className="detail-label">Probable Fix</span>
              <span className="detail-value">{incident.aiOutput.probableFix ? 'Yes' : 'No'}</span>
              <span className="detail-label">Hypothesis</span>
              <span className="detail-value">{incident.aiOutput.hypothesis}</span>
            </div>
            {incident.aiOutput.summary && (
              <div className="code-block">{incident.aiOutput.summary}</div>
            )}
            {incident.aiOutput.files && Object.keys(incident.aiOutput.files).length > 0 && (
              <>
                <h3 style={{ marginTop: 12 }}>Changed Files</h3>
                <ul className="files-list">
                  {Object.keys(incident.aiOutput.files).map(f => <li key={f}>{f}</li>)}
                </ul>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

const MOCK_INCIDENTS = [
  {
    incidentId: "SLACK-17243567890123",
    jiraKey: "HACK-42",
    jiraUrl: "https://jungleegames.atlassian.net/browse/HACK-42",
    status: "COMPLETED",
    timestamp: new Date(Date.now() - 3600000).toISOString(),
    fixPullRequest: { number: 128, url: "https://github.com/jungleegames/reward-service/pull/128" },
    revertPullRequest: { number: 127, url: "https://github.com/jungleegames/reward-service/pull/127" },
    signalFxExports: { errorRateBefore: 0.5, errorRateDuring: 12.3, latencyChanged: true, dashboardUrl: "https://app.eu0.signalfx.com/#/dashboard/demo" },
    splunkLogs: { errorCount: 47, topError: "java.lang.NullPointerException: Cannot invoke method on null reference", samples: [
      "2026-08-22 10:14:02 ERROR [reward-service] c.f.r.DatabaseConnectionPool - Connection pool exhausted, max=10 active=10 idle=0",
      "2026-08-22 10:14:03 ERROR [reward-service] c.f.r.RewardController - Failed to process reward request",
      "2026-08-22 10:14:05 ERROR [reward-service] c.f.r.DatabaseConnectionPool - Timeout waiting for connection after 30000ms"
    ], sourceUrl: "https://splunk.example/app/search" },
    aiOutput: { probableFix: true, hypothesis: "Database connection pool exhaustion due to leaked connections in RewardController", summary: "The connection pool maxes out at 10 connections. The RewardController.processReward() method opens a connection but fails to close it in the catch block, causing a leak under error conditions. Fix: add try-with-resources to ensure connections are always returned to the pool.", files: { "src/main/java/com/flutter/reward_service/RewardController.java": "Added try-with-resources block", "src/main/java/com/flutter/reward_service/config/DatabaseConfig.java": "Increased pool size to 20 and added leak detection" } }
  },
  {
    incidentId: "SLACK-17243891234567",
    jiraKey: "HACK-43",
    jiraUrl: "https://jungleegames.atlassian.net/browse/HACK-43",
    status: "AI_INVESTIGATING",
    timestamp: new Date(Date.now() - 1200000).toISOString(),
    signalFxExports: { errorRateBefore: 1.2, errorRateDuring: 8.7, latencyChanged: false, dashboardUrl: "https://app.eu0.signalfx.com/#/dashboard/demo" },
    splunkLogs: { errorCount: 23, topError: "redis.clients.jedis.exceptions.JedisConnectionException: Connection refused", samples: [
      "2026-08-22 11:32:01 ERROR [darsrftp-service] c.f.d.cache.RedisCacheManager - Failed to connect to Redis at 10.0.1.50:6379",
      "2026-08-22 11:32:02 ERROR [darsrftp-service] c.f.d.FtpTransferService - Cache lookup failed, falling back to DB"
    ], sourceUrl: "https://splunk.example/app/search" },
    aiOutput: null
  },
  {
    incidentId: "P2UX5VH-003",
    jiraKey: "HACK-44",
    jiraUrl: "https://jungleegames.atlassian.net/browse/HACK-44",
    status: "DRAFT_PR_CREATED",
    timestamp: new Date(Date.now() - 2400000).toISOString(),
    fixPullRequest: { number: 131, url: "https://github.com/jungleegames/reward-service/pull/131" },
    revertPullRequest: { number: 129, url: "https://github.com/jungleegames/reward-service/pull/129" },
    signalFxExports: { errorRateBefore: 2.1, errorRateDuring: 15.6, latencyChanged: true, dashboardUrl: "https://app.eu0.signalfx.com/#/dashboard/demo" },
    splunkLogs: { errorCount: 112, topError: "com.flutter.reward_service.exceptions.PaymentTimeoutException", samples: [
      "2026-08-22 10:55:12 ERROR [reward-service] c.f.r.PaymentGateway - Payment API timeout after 5000ms",
      "2026-08-22 10:55:14 ERROR [reward-service] c.f.r.PaymentGateway - Retry 1/3 failed",
      "2026-08-22 10:55:18 ERROR [reward-service] c.f.r.PaymentGateway - Circuit breaker OPEN for payment-api"
    ], sourceUrl: "https://splunk.example/app/search" },
    aiOutput: { probableFix: true, hypothesis: "Payment gateway timeout threshold too aggressive, circuit breaker opens prematurely", summary: "The payment gateway client has a 5s timeout which is too aggressive for peak hours. The circuit breaker opens after 3 failures within 30s. Increasing timeout to 10s and adjusting the circuit breaker threshold should resolve the cascading failures.", files: { "src/main/java/com/flutter/reward_service/PaymentGateway.java": "Increased timeout to 10s, adjusted retry backoff" } }
  },
  {
    incidentId: "SLACK-17244012345678",
    jiraKey: "HACK-45",
    jiraUrl: "https://jungleegames.atlassian.net/browse/HACK-45",
    status: "FAILED",
    timestamp: new Date(Date.now() - 7200000).toISOString(),
    signalFxExports: { errorRateBefore: 0.1, errorRateDuring: 0.8, latencyChanged: false, dashboardUrl: null },
    splunkLogs: { errorCount: 5, topError: "java.io.IOException: Disk quota exceeded", samples: [
      "2026-08-22 09:10:44 ERROR [darsrftp-service] c.f.d.storage.FileWriter - Cannot write to /data/exports: Disk quota exceeded"
    ], sourceUrl: "https://splunk.example/app/search" },
    aiOutput: null
  },
  {
    incidentId: "SLACK-17244123456789",
    jiraKey: null,
    jiraUrl: null,
    status: "RECEIVED",
    timestamp: new Date(Date.now() - 300000).toISOString(),
    signalFxExports: null,
    splunkLogs: null,
    aiOutput: null
  },
  {
    incidentId: "P2UX5VH-005",
    jiraKey: "HACK-46",
    jiraUrl: "https://jungleegames.atlassian.net/browse/HACK-46",
    status: "JIRA_CONTEXT_PUBLISHED",
    timestamp: new Date(Date.now() - 900000).toISOString(),
    signalFxExports: { errorRateBefore: 3.4, errorRateDuring: 22.1, latencyChanged: true, dashboardUrl: "https://app.eu0.signalfx.com/#/dashboard/demo" },
    splunkLogs: { errorCount: 89, topError: "org.springframework.dao.DataIntegrityViolationException: Duplicate entry", samples: [
      "2026-08-22 11:47:22 ERROR [reward-service] c.f.r.UserRewardDao - Duplicate key violation on user_rewards (user_id, campaign_id)",
      "2026-08-22 11:47:23 ERROR [reward-service] c.f.r.RewardService - Failed to grant reward to user 8847123"
    ], sourceUrl: "https://splunk.example/app/search" },
    aiOutput: null
  }
]

function computeStats(incidents) {
  const pipeline = {}
  let completed = 0, failed = 0
  for (const inc of incidents) {
    pipeline[inc.status] = (pipeline[inc.status] || 0) + 1
    if (inc.status === 'COMPLETED') completed++
    if (inc.status === 'FAILED') failed++
  }
  return { total: incidents.length, completed, failed, active: incidents.length - completed - failed, pipeline }
}

export default function App() {
  const [incidents, setIncidents] = useState(MOCK_INCIDENTS)
  const [stats, setStats] = useState(computeStats(MOCK_INCIDENTS))
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState(null)
  const [live, setLive] = useState(false)

  const fetchData = useCallback(async () => {
    try {
      const [incRes, statsRes] = await Promise.all([
        fetch('/api/dashboard/incidents'),
        fetch('/api/dashboard/stats'),
      ])
      if (incRes.ok && statsRes.ok) {
        setIncidents(await incRes.json())
        setStats(await statsRes.json())
        setLive(true)
      }
    } catch {
      // backend not available — keep showing mock data
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 15000)
    return () => clearInterval(interval)
  }, [fetchData])

  const handleRefresh = () => {
    setRefreshing(true)
    fetchData()
  }

  if (loading) {
    return (
      <div className="dashboard">
        <div className="loading">
          <div className="loading-spinner" />
          Loading incidents...
        </div>
      </div>
    )
  }

  return (
    <div className="dashboard">
      <div className="bg-dots" />
      <div className="header">
        <h1>Incident Remediation</h1>
        <div className="header-right">
          <button className={`refresh-btn ${refreshing ? 'spinning' : ''}`} onClick={handleRefresh}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 2v6h-6M3 12a9 9 0 0 1 15-6.7L21 8M3 22v-6h6M21 12a9 9 0 0 1-15 6.7L3 16" />
            </svg>
            Refresh
          </button>
        </div>
      </div>

      <StatsCards stats={stats} />

      <div className="content-grid">
        <IncidentsTable incidents={incidents} onSelect={setSelected} />
        <Timeline incidents={incidents} />
      </div>

      <DetailModal incident={selected} onClose={() => setSelected(null)} />
    </div>
  )
}
