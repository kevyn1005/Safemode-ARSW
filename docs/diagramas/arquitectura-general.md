# Arquitectura general (estado actual)

```mermaid
flowchart LR
    subgraph Computador["En el computador (necesita la pantalla real)"]
        CF[camera-feed-service<br/>Robot screen capture]
        VD[vision-detection-service<br/>YOLOv8 + ONNX Runtime]
        OPT[object-presence-tracker<br/>duenos, retiros, fotos]
    end
    subgraph Docker["docker compose"]
        DB[(h2-db<br/>H2 modo servidor)]
        subgraph GW["realtime-gateway (un solo proceso)"]
            AE[alert-engine<br/>reglas de alerta]
            WS[WebSocket + HTTP]
        end
    end
    SD[security-dashboard<br/>index.html en el navegador]

    CF -- "getLatestFrame()" --> VD
    VD -- "detecciones" --> OPT
    OPT -- "eventos (TCP, jdbc:h2:tcp)" --> DB
    DB -- "retiros nuevos" --> AE
    AE -- "alerta nueva" --> WS
    WS -- "WebSocket (tiempo real)" --> SD
    WS -. "HTTP: pagina y fotos" .-> SD
    OPT -. "fotos en disco (volumen, solo lectura)" .-> WS
```

- Linea solida: comunicacion implementada. Dentro del computador es una llamada directa en memoria; entre el
  tracker, la base y el gateway es por red (sockets TCP); del gateway al panel es un WebSocket.
- Linea punteada: el navegador pide la pagina y las fotos por HTTP; las fotos las escribe el tracker en el disco del
  computador y el contenedor las lee montadas en solo lectura.
- `alert-engine` y `security-dashboard` no tienen contenedor propio: el motor corre dentro del proceso del gateway y el
  panel es un `index.html` que el gateway entrega.
- Sin Docker tambien funciona: si no se define `SAFEMODE_DB_URL`, el tracker y el gateway comparten el archivo H2 local
  (`AUTO_SERVER`), como antes.

Ver [ADR 0001](../adr/0001-contenerizacion-servicios-captura-pantalla.md) para como se contenerizan los servicios de
captura, y [ADR 0002](../adr/0002-docker-compose-gateway-y-base-de-datos.md) para la base de datos compartida y el
gateway.
