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
