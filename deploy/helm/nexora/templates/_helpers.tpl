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

{{- define "nexora.apiKeySecretName" -}}
{{- default (include "nexora.secretName" .) .Values.apiKey.existingSecret }}
{{- end }}

{{- define "nexora.jdbcSecretName" -}}
{{- default (include "nexora.secretName" .) .Values.persistence.jdbc.existingSecret }}
{{- end }}

{{- define "nexora.createSecret" -}}
{{- if or (and (not .Values.apiKey.existingSecret) .Values.apiKey.value) (and (eq .Values.persistence.mode "jdbc") (not .Values.persistence.jdbc.existingSecret) .Values.persistence.jdbc.url) }}true{{- end }}
{{- end }}

{{/* The WebSocket port is fixed by the server: the HTTP port plus one. */}}
{{- define "nexora.websocketPort" -}}
{{- add .Values.service.httpPort 1 }}
{{- end }}

{{- define "nexora.scheme" -}}
{{- if .Values.tls.enabled }}HTTPS{{ else }}HTTP{{ end }}
{{- end }}

{{/* nexora.json: the user's config, plus the H2 file location when persistence.mode is h2. */}}
{{- define "nexora.configJson" -}}
{{- $config := deepCopy (.Values.config | default dict) }}
{{- if eq .Values.persistence.mode "h2" }}
{{- $_ := set $config "executionStore" "/data/nexora" }}
{{- end }}
{{- toPrettyJson $config }}
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
{{- if and (not .Values.persistence.jdbc.existingSecret) (not .Values.persistence.jdbc.url) }}
{{- fail "persistence.mode=jdbc needs persistence.jdbc.existingSecret (a Secret with the JDBC URL) or persistence.jdbc.url" }}
{{- end }}
{{- end }}
{{- if eq $mode "h2" }}
{{- if or (gt (int .Values.replicaCount) 1) .Values.autoscaling.enabled }}
{{- fail "persistence.mode=h2 keeps the store on one ReadWriteOnce volume, so it runs a single replica. Use persistence.mode=jdbc to run more than one, or set replicaCount=1 and autoscaling.enabled=false" }}
{{- end }}
{{- end }}
{{- if and .Values.tls.enabled (not .Values.tls.existingSecret) }}
{{- fail "tls.enabled needs tls.existingSecret, a kubernetes.io/tls Secret with tls.crt and tls.key" }}
{{- end }}
{{- end }}
