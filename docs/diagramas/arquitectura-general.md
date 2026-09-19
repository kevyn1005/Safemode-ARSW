# Arquitectura general (estado actual)

```mermaid
flowchart LR
    subgraph Implementado
        CF[camera-feed-service<br/>Robot screen capture]
        VD[vision-detection-service<br/>YOLOv8 + ONNX Runtime]
    end
    subgraph Pendiente
        OPT[object-presence-tracker]
        AE[alert-engine]
        RG[realtime-gateway]
        SD[security-dashboard]
    end

    CF -- "getLatestFrame()" --> VD
    VD -.-> OPT
    OPT -.-> AE
    AE -.-> RG
    RG -.-> SD
```

- Linea solida: comunicacion ya implementada (llamada directa en memoria
  via `FrameCapturer.getLatestFrame()`).
- Linea punteada: comunicacion planeada, todavia sin implementar (esos
  modulos solo tienen el `pom.xml`/`package.json` base).

Ver [ADR 0001](../adr/0001-contenerizacion-servicios-captura-pantalla.md)
para el detalle de como se contenerizan los dos servicios implementados.
