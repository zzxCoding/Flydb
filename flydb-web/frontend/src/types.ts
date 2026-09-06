export interface Profile {
  id: string; name: string; environment: string; group: string
  configPath: string; workingDirectory: string; driversDirectory?: string
}
export interface ConfigSnapshot {
  revision: string; values: Record<string, string>; effective: Record<string, string>
  sources: Record<string, string>; error?: { code: string; detail: string }; secretKeys: string[]
}
export interface Migration {
  script: string; version: string | null; description: string; state?: string
  type: string; checksum?: number; statementCount?: number
  statements?: { lineNumber: number; sql: string }[]
}
export interface CommandResult {
  status?: string; current?: string; databaseName?: string; migrations?: Migration[]
  plan?: { id: string; migrationCount: number; statementCount: number; targetVersion: string | null }
  planId?: string; error?: { code: string; detail: string }; executed?: string[]
  inspection?: CommandResult; validation?: CommandResult; verificationError?: string
  [key: string]: unknown
}
export interface Run {
  id: string; profileId?: string; profileName?: string; configPath?: string
  source: 'CLI' | 'WEB'; command: string; status: string; startedAt: string
  endedAt?: string; lastActivityAt?: string; target?: string; script?: string
  verification: string; sequence: number; result?: CommandResult; recovery?: string
  configRevision?: string; driver?: { className: string; source: string }
  progress?: { confirmed: number; total: number; script: string }
  transactionResult?: { transaction: string; phase: string; script: string }
}
export interface Bootstrap {
  profiles: Profile[]; runs: Run[]; version: string; initialDirectory: string
  stateDirectory: string; driversDirectory: string; knownKeys: string[]
}
export interface FileEntry { path: string; name: string; directory: boolean }
