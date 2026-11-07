# Tablero SafeMode de Grafana (Kevyn)

El tablero SafeMode que armo Kevyn en la rama `kevynobservabilidad` **no esta en el repositorio**: se construyo en la interfaz de su Grafana y
quedo solo en el volumen de su computador. Esta carpeta es donde va para que `docker-compose.persistencia.yml` lo cargue **tal cual, sin
modificarlo**, en la carpeta **SafeMode** de Grafana.

## Como exportarlo (Kevyn)

1. Con su Grafana (el de `docker-compose.observability.yml`) abra el tablero SafeMode.
2. **Share** -> **Export** -> **Export as JSON**. Deje **desactivada** la opcion "Export for sharing externally": asi conserva el `uid` de la
   fuente de datos `prometheus` y los paneles siguen funcionando aqui sin cambios.
3. Guarde el archivo en esta carpeta (por ejemplo `safemode.json`) y haga commit.

## Que ya es compatible con sus paneles

El Prometheus de `docker-compose.persistencia.yml` usa los **mismos nombres de job** que el suyo (`gateway` y `ready-probe`) y el gateway
expone las mismas metricas que el (`safemode_alerts_total{severity}`, `safemode_alert_latency_seconds_*`, JVM y `probe_success`); no se
cambio ninguna. Lo unico distinto es la etiqueta `instance` (aqui el gateway es `realtime-gateway:9464`, en su compose
`host.docker.internal:9464`): si algun panel filtra o nombra por `instance`, hay que ajustarlo. Si solo usa `job` (lo normal), funciona igual.
