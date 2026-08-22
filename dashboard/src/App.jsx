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

function formatTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  return d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })
}

function formatMetric(v) {
  if (!Number.isFinite(v)) return '-'
  const abs = Math.abs(v)
  if (abs >= 1000) return v.toLocaleString('en-IN', { maximumFractionDigits: 0 })
  if (abs >= 10) return v.toFixed(0)
  if (abs >= 1) return v.toFixed(1)
  if (v === 0) return '0'
  return Number(v.toPrecision(2)).toString()
}

/**
 * SignalFx returns each point with its label dimensions alongside timestampMs/value, so any other
 * string field (service.name, k8s.namespace.name, ...) is what splits one program's points into
 * series.
 */
function seriesLabelOf(point) {
  for (const [key, value] of Object.entries(point)) {
    if (key !== 'timestampMs' && key !== 'value' && typeof value === 'string') return value
  }
  return 'value'
}

/** rawPoints is stored as a JSON string, and is null when it was too large to persist. */
function parseRawPoints(rawPoints) {
  if (!rawPoints) return []

  let parsed
  try {
    parsed = JSON.parse(rawPoints)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []

  const bySeries = new Map()
  for (const point of parsed) {
    if (!Number.isFinite(point?.timestampMs) || !Number.isFinite(point?.value)) continue
    const label = seriesLabelOf(point)
    if (!bySeries.has(label)) bySeries.set(label, [])
    bySeries.get(label).push({ t: point.timestampMs, v: point.value })
  }

  return [...bySeries.entries()]
    .map(([label, points]) => ({ label, points: points.sort((a, b) => a.t - b.t) }))
    .filter(s => s.points.length > 0)
}

function niceCeil(value) {
  if (!(value > 0)) return 1
  const magnitude = 10 ** Math.floor(Math.log10(value))
  const normalized = value / magnitude
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * magnitude
}

function useElementWidth() {
  const ref = useRef(null)
  const [width, setWidth] = useState(0)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    setWidth(el.clientWidth)
    const observer = new ResizeObserver(entries => setWidth(entries[0].contentRect.width))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  return [ref, width]
}

// One hue, stepped by lightness, each step paired with its own dash pattern so overlapping series
// stay separable without color alone and without adding a second hue to this UI.
const SERIES_STYLES = [
  { color: 'var(--chart-series-1)', dash: undefined },
  { color: 'var(--chart-series-2)', dash: '7 4' },
  { color: 'var(--chart-series-3)', dash: '2 3' },
]

const CHART_HEIGHT = 180
const PAD = { top: 16, right: 16, bottom: 26, left: 48 }

function MetricChart({ series, label }) {
  const [wrapRef, width] = useElementWidth()
  const [hoverIndex, setHoverIndex] = useState(null)

  const axis = [...new Set(series.flatMap(s => s.points.map(p => p.t)))].sort((a, b) => a - b)
  const tMin = axis[0]
  const tMax = axis[axis.length - 1]
  const yMax = niceCeil(Math.max(...series.flatMap(s => s.points.map(p => p.v))))

  const innerW = Math.max(width - PAD.left - PAD.right, 10)
  const innerH = CHART_HEIGHT - PAD.top - PAD.bottom

  const xOf = t => (tMax === tMin ? PAD.left + innerW / 2 : PAD.left + ((t - tMin) / (tMax - tMin)) * innerW)
  const yOf = v => PAD.top + innerH - (v / yMax) * innerH

  const handleMove = e => {
    const box = e.currentTarget.getBoundingClientRect()
    const x = e.clientX - box.left
    let nearest = 0
    for (let i = 1; i < axis.length; i++) {
      if (Math.abs(xOf(axis[i]) - x) < Math.abs(xOf(axis[nearest]) - x)) nearest = i
    }
    setHoverIndex(nearest)
  }

  const hoverT = hoverIndex === null ? null : axis[hoverIndex]
  const hoverX = hoverT === null ? 0 : xOf(hoverT)
  const hoverRows = hoverT === null
    ? []
    : series
      .map((s, i) => ({ label: s.label, style: SERIES_STYLES[i % SERIES_STYLES.length], point: s.points.find(p => p.t === hoverT) }))
      .filter(row => row.point)

  const yTicks = [0, yMax / 2, yMax]

  return (
    <div className="chart-wrap" ref={wrapRef}>
      {series.length > 1 && (
        <div className="chart-legend">
          {series.map((s, i) => {
            const style = SERIES_STYLES[i % SERIES_STYLES.length]
            return (
              <span className="chart-legend-item" key={s.label}>
                <svg width="18" height="8" aria-hidden="true">
                  <line x1="0" y1="4" x2="18" y2="4" stroke={style.color} strokeWidth="2"
                        strokeDasharray={style.dash} />
                </svg>
                {s.label}
              </span>
            )
          })}
        </div>
      )}

      {width > 0 && (
        <svg
          width={width}
          height={CHART_HEIGHT}
          role="img"
          aria-label={`${label}: ${formatMetric(Math.min(...series.flatMap(s => s.points.map(p => p.v))))} to ${formatMetric(Math.max(...series.flatMap(s => s.points.map(p => p.v))))} between ${formatClock(tMin)} and ${formatClock(tMax)}`}
          onMouseMove={handleMove}
          onMouseLeave={() => setHoverIndex(null)}
        >
          {yTicks.map(tick => (
            <g key={tick}>
              <line className="chart-grid" x1={PAD.left} y1={yOf(tick)} x2={PAD.left + innerW} y2={yOf(tick)} />
              <text className="chart-axis-label" x={PAD.left - 8} y={yOf(tick)} textAnchor="end" dominantBaseline="middle">
                {formatMetric(tick)}
              </text>
            </g>
          ))}

          <text className="chart-axis-label" x={PAD.left} y={CHART_HEIGHT - 8}>{formatClock(tMin)}</text>
          <text className="chart-axis-label" x={PAD.left + innerW} y={CHART_HEIGHT - 8} textAnchor="end">
            {formatClock(tMax)}
          </text>

          {series.map((s, i) => {
            const style = SERIES_STYLES[i % SERIES_STYLES.length]
            const path = s.points.map((p, idx) => `${idx === 0 ? 'M' : 'L'}${xOf(p.t)},${yOf(p.v)}`).join(' ')
            return (
              <g key={s.label}>
                {series.length === 1 && s.points.length > 1 && (
                  <path
                    className="chart-area"
                    d={`${path} L${xOf(s.points[s.points.length - 1].t)},${yOf(0)} L${xOf(s.points[0].t)},${yOf(0)} Z`}
                  />
                )}
                <path d={path} fill="none" stroke={style.color} strokeWidth="2" strokeDasharray={style.dash}
                      strokeLinejoin="round" strokeLinecap="round" />
                {s.points.length === 1 && (
                  <circle cx={xOf(s.points[0].t)} cy={yOf(s.points[0].v)} r="4" fill={style.color} />
                )}
              </g>
            )
          })}

          {hoverT !== null && (
            <g>
              <line className="chart-crosshair" x1={hoverX} y1={PAD.top} x2={hoverX} y2={PAD.top + innerH} />
              {hoverRows.map(row => (
                <circle key={row.label} cx={hoverX} cy={yOf(row.point.v)} r="4"
                        fill={row.style.color} stroke="var(--surface)" strokeWidth="2" />
              ))}
            </g>
          )}
        </svg>
      )}

      {hoverT !== null && hoverRows.length > 0 && (
        <div
          className="chart-tooltip"
          style={{ left: Math.min(Math.max(hoverX, 60), Math.max(width - 60, 60)) }}
        >
          <div className="chart-tooltip-time">{formatClock(hoverT)}</div>
          {hoverRows.map(row => (
            <div className="chart-tooltip-row" key={row.label}>
              <span className="chart-tooltip-label">{row.label}</span>
              <span className="chart-tooltip-value">{formatMetric(row.point.v)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function ProgramCharts({ programExports }) {
  return (
    <div className="detail-section">
      <h3>SignalFx Programs</h3>
      {programExports.map(exp => {
        const series = parseRawPoints(exp.rawPoints)
        return (
          <div className="chart-card" key={exp.program}>
            <div className="chart-card-head">
              <span className="chart-card-title">{exp.program}</span>
              <span className="chart-card-meta">{exp.pointCount} pts</span>
            </div>
            <div className="chart-card-filter">{exp.filter}</div>
            {exp.error ? (
              <div className="chart-empty">{exp.error}</div>
            ) : series.length > 0 ? (
              <MetricChart series={series} label={exp.program} />
            ) : (
              <div className="chart-empty">No points stored for this program</div>
            )}
          </div>
        )
      })}
    </div>
  )
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


function PullRequestLinks({ incident }) {
  const { fixPullRequest, revertPullRequest, lastPullRequest } = incident
  if (!fixPullRequest && !revertPullRequest && !lastPullRequest) {
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
      {lastPullRequest && (
        <a className="pr-link suspect" href={lastPullRequest.url} target="_blank" rel="noreferrer"
           title={lastPullRequest.title || undefined}
           onClick={e => e.stopPropagation()}>
          Suspect #{lastPullRequest.number}
        </a>
      )}
    </div>
  )
}

function IncidentsTable({ incidents, onSelect }) {
  return (
    <div className="table-section">
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
              <th>Latency</th>
              <th>Time</th>
            </tr>
          </thead>
          <tbody>
            {incidents.length === 0 && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 40 }}>No incidents yet</td></tr>
            )}
            {incidents.map(inc => (
              <tr key={inc.incidentId} onClick={() => onSelect(inc)}>
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

          <span className="detail-label">Suspect PR</span>
          <span className="detail-value">
            {incident.lastPullRequest ? (
              <a className="pr-link suspect" href={incident.lastPullRequest.url} target="_blank" rel="noreferrer">
                #{incident.lastPullRequest.number}
                {incident.lastPullRequest.title ? ` ${incident.lastPullRequest.title}` : ''}
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
                      Open SignalFx Dashboard
                    </a>
                  </span>
                </>
              )}
            </>
          )}
        </div>

        {incident.signalFxExports?.exports?.length > 0 && (
          <ProgramCharts programExports={incident.signalFxExports.exports} />
        )}

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

  const handleSelect = useCallback(async (inc) => {
    setSelected(inc)
    if (!live) return
    try {
      const res = await fetch(`/api/dashboard/incidents/${encodeURIComponent(inc.incidentId)}`)
      if (res.ok) setSelected(await res.json())
    } catch {
      // keep showing the row's (rawPoints-trimmed) data
    }
  }, [live])

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

      <IncidentsTable incidents={incidents} onSelect={handleSelect} />

      <DetailModal incident={selected} onClose={() => setSelected(null)} />
    </div>
  )
}
