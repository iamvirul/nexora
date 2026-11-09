{{/*
Names, labels and validation shared by the templates.
*/}}

{{- define "nexora.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexora.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "nexora.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Values are quoted: an unquoted release name such as "n" or "on" would be read as a boolean. */}}
{{- define "nexora.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nexora.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end }}

{{- define "nexora.labels" -}}
helm.sh/chart: {{ include "nexora.chart" . | quote }}
{{ include "nexora.selectorLabels" . }}
app.kubernetes.io/version: {{ include "nexora.imageTag" . | quote }}
app.kubernetes.io/component: observe-server
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end }}

{{- define "nexora.imageTag" -}}
{{- default .Chart.AppVersion .Values.image.tag }}
{{- end }}

{{- define "nexora.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "nexora.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/* The chart-managed Secret, created only for values given inline. */}}
{{- define "nexora.secretName" -}}
{{- printf "%s-secrets" (include "nexora.fullname" .) }}
{{- end }}

{{/* An inline apiKey.value wins over existingSecret, whose non-empty default would otherwise hide it. */}}
{{- define "nexora.apiKeySecretName" -}}
{{- if .Values.apiKey.value }}{{ include "nexora.secretName" . }}{{ else }}{{ .Values.apiKey.existingSecret }}{{ end }}
{{- end }}

{{- define "nexora.jdbcSecretName" -}}
{{- default (include "nexora.secretName" .) .Values.persistence.jdbc.existingSecret }}
{{- end }}

{{- define "nexora.createSecret" -}}
{{- if or .Values.apiKey.value (and (eq .Values.persistence.mode "jdbc") .Values.persistence.jdbc.url) }}true{{- end }}
{{- end }}

{{/*
The embedded H2 store, as the URL the CLI builds for executionStore. Passed in the environment, not in
nexora.json: the CLI drops an unparseable nexora.json for its defaults, and a typo in `config` would then
drop the store with it, leaving the pod serving from memory.
*/}}
{{- define "nexora.h2JdbcUrl" -}}
jdbc:h2:/data/nexora;AUTO_SERVER=TRUE
{{- end }}

{{/* The WebSocket port is fixed by the server: the HTTP port plus one. */}}
{{- define "nexora.websocketPort" -}}
{{- add .Values.service.httpPort 1 }}
{{- end }}

{{- define "nexora.scheme" -}}
{{- if .Values.tls.enabled }}HTTPS{{ else }}HTTP{{ end }}
{{- end }}

{{/* nexora.json: the user's config as given. The store never goes in it; see nexora.h2JdbcUrl. */}}
{{- define "nexora.configJson" -}}
{{- toPrettyJson (.Values.config | default dict) }}
{{- end }}

{{/*
Refuses settings that would deploy but not work. Rendered from the Deployment, so `helm install`,
`helm template` and `helm lint` all fail with the reason.
*/}}
{{- define "nexora.validate" -}}
{{- $mode := .Values.persistence.mode }}
{{- if not (has $mode (list "h2" "jdbc" "none")) }}
{{- fail (printf "persistence.mode must be h2, jdbc or none, got %q" $mode) }}
{{- end }}
{{- if and (not .Values.apiKey.existingSecret) (not .Values.apiKey.value) }}
{{- fail "Set apiKey.existingSecret to a Secret holding the API key (recommended), or apiKey.value. Create one with: kubectl create secret generic nexora-api-key --from-literal=api-key=\"$(openssl rand -base64 36)\"" }}
{{- end }}
{{- if and .Values.apiKey.value (lt (len .Values.apiKey.value) 32) }}
{{- fail "apiKey.value must be at least 32 characters" }}
{{- end }}
{{- if hasKey (.Values.config | default dict) "executionStore" }}
{{- fail "Do not set executionStore in config; persistence.mode chooses the store" }}
{{- end }}
{{- if eq $mode "jdbc" }}
{{- if and .Values.persistence.jdbc.existingSecret .Values.persistence.jdbc.url }}
{{- fail "Set one of persistence.jdbc.existingSecret and persistence.jdbc.url, not both" }}
{{- end }}
{{- if and (not .Values.persistence.jdbc.existingSecret) (not .Values.persistence.jdbc.url) }}
{{- fail "persistence.mode=jdbc needs persistence.jdbc.existingSecret (a Secret with the JDBC URL) or persistence.jdbc.url" }}
{{- end }}
{{- end }}
{{- if and (eq $mode "h2") (or (gt (int .Values.replicaCount) 1) .Values.autoscaling.enabled) }}
{{- fail "persistence.mode=h2 keeps the store on one ReadWriteOnce volume, so it runs a single replica. Use persistence.mode=jdbc to run more than one, or set replicaCount=1 and autoscaling.enabled=false" }}
{{- end }}
{{- if and (eq $mode "none") (or (gt (int .Values.replicaCount) 1) .Values.autoscaling.enabled) }}
{{- fail "persistence.mode=none keeps each pod's executions in its own memory, so more than one replica would be separate servers with separate histories. Use persistence.mode=jdbc to run more than one, or set replicaCount=1 and autoscaling.enabled=false" }}
{{- end }}
{{- if .Values.podDisruptionBudget.enabled }}
{{- $replicas := ternary (int .Values.autoscaling.minReplicas) (int .Values.replicaCount) .Values.autoscaling.enabled }}
{{- if le $replicas (int .Values.podDisruptionBudget.minAvailable) }}
{{- fail (printf "podDisruptionBudget.minAvailable=%d with %d replica(s) allows no voluntary disruption, so node drains would hang. Run more replicas than minAvailable (replicaCount, or autoscaling.minReplicas with autoscaling on), or leave podDisruptionBudget.enabled=false" (int .Values.podDisruptionBudget.minAvailable) $replicas) }}
{{- end }}
{{- end }}
{{- if and .Values.tls.enabled (not .Values.tls.existingSecret) }}
{{- fail "tls.enabled needs tls.existingSecret, a kubernetes.io/tls Secret with tls.crt and tls.key" }}
{{- end }}
{{- end }}
