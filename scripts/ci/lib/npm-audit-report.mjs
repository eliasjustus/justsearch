export const NPM_AUDIT_VULNERABILITY_KEYS = [
  'info',
  'low',
  'moderate',
  'high',
  'critical',
  'total',
]

export const NPM_AUDIT_DEPENDENCY_KEYS = [
  'prod',
  'dev',
  'optional',
  'peer',
  'peerOptional',
  'total',
]

export const NPM_AUDIT_REQUIRED_TARGET_IDS = Object.freeze(['root', 'ui-web'])
export const NPM_AUDIT_OPTIONAL_TARGET_IDS = Object.freeze(['ssot-tools'])

function isPlainRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function isCompleteCountRecord(value, requiredKeys) {
  return (
    isPlainRecord(value) &&
    requiredKeys.every((key) => Number.isInteger(value[key]) && value[key] >= 0)
  )
}

export function isNpmAuditPayload(value) {
  return (
    isPlainRecord(value) &&
    isPlainRecord(value.metadata) &&
    isCompleteCountRecord(value.metadata.vulnerabilities, NPM_AUDIT_VULNERABILITY_KEYS) &&
    isCompleteCountRecord(value.metadata.dependencies, NPM_AUDIT_DEPENDENCY_KEYS)
  )
}

export function auditTargetUnavailableReason(row) {
  if (!isPlainRecord(row)) return 'required audit target is missing from the report'
  if (row.available !== true) {
    return row.parse_error || row.command_error || row.signal || 'audit evidence is marked unavailable'
  }
  if (row.command_error) return row.command_error
  if (row.signal) return String(row.signal)
  if (row.applicable === false) {
    const targetId = String(row.target_id ?? '').trim()
    if (!NPM_AUDIT_OPTIONAL_TARGET_IDS.includes(targetId)) {
      return `${targetId || 'unnamed target'} is not allowed to be non-applicable`
    }
    if (
      !isCompleteCountRecord(row.vulnerabilities, NPM_AUDIT_VULNERABILITY_KEYS) ||
      !isCompleteCountRecord(row.dependencies, NPM_AUDIT_DEPENDENCY_KEYS)
    ) {
      return 'non-applicable audit target has incomplete count metadata'
    }
    return null
  }
  if (row.applicable !== true) return 'audit applicability is missing'
  if (row.parsed !== true) return row.parse_error || 'audit output was not parsed'
  if (!isCompleteCountRecord(row.vulnerabilities, NPM_AUDIT_VULNERABILITY_KEYS)) {
    return 'vulnerability metadata is incomplete'
  }
  if (!isCompleteCountRecord(row.dependencies, NPM_AUDIT_DEPENDENCY_KEYS)) {
    return 'dependency metadata is incomplete'
  }
  return null
}
