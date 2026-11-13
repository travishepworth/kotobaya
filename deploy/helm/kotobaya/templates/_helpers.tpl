{{- define "kotobaya.image" -}}
{{ .root.Values.imageRegistry }}/{{ .img.repository }}:{{ .img.tag }}
{{- end -}}
{{- define "kotobaya.labels" -}}
app.kubernetes.io/name: kotobaya
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "kotobaya.selector" -}}
app.kubernetes.io/name: kotobaya
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}
{{- define "kotobaya.s3endpoint" -}}
{{- if .Values.objectStore.enabled -}}http://{{ .Release.Name }}-objectstore:8333{{- else -}}{{ .Values.objectStore.endpoint }}{{- end -}}
{{- end -}}
{{- define "kotobaya.appEnv" -}}
- name: DATABASE_URL
  valueFrom: { secretKeyRef: { name: {{ .Values.secrets.appSecretName }}, key: DATABASE_URL } }
- name: S3_ENDPOINT
  value: {{ include "kotobaya.s3endpoint" . | quote }}
- name: S3_REGION
  value: {{ .Values.objectStore.region | quote }}
- name: S3_BUCKET
  value: {{ .Values.objectStore.bucket | quote }}
- name: S3_ACCESS_KEY
  valueFrom: { secretKeyRef: { name: {{ .Values.secrets.appSecretName }}, key: S3_ACCESS_KEY } }
- name: S3_SECRET_KEY
  valueFrom: { secretKeyRef: { name: {{ .Values.secrets.appSecretName }}, key: S3_SECRET_KEY } }
{{- end -}}
{{- define "kotobaya.migrateContainer" -}}
name: migrate
image: {{ include "kotobaya.image" (dict "root" . "img" .Values.api.image) }}
imagePullPolicy: {{ .Values.imagePullPolicy }}
securityContext: {{- toYaml .Values.containerSecurityContext | nindent 2 }}
command: {{- toYaml .Values.migrate.command | nindent 2 }}
env:
  - name: DATABASE_URL
    valueFrom: { secretKeyRef: { name: {{ .Values.secrets.appSecretName }}, key: DATABASE_URL } }
resources: {{- toYaml .Values.migrate.resources | nindent 2 }}
volumeMounts: [{ name: tmp, mountPath: /tmp }]
{{- end -}}
{{- /* Pods of this release with one of the given components (NetworkPolicy peer / podSelector body). */ -}}
{{- define "kotobaya.components" -}}
matchLabels: { app.kubernetes.io/name: kotobaya, app.kubernetes.io/instance: {{ .root.Release.Name }} }
matchExpressions:
  - { key: app.kubernetes.io/component, operator: In, values: [{{ join ", " .components }}] }
{{- end -}}
