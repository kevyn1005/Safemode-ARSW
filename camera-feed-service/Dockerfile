# Build con contexto en la raiz del repo (ver docker-compose.yml): este
# modulo pertenece a un reactor Maven multi-modulo y necesita el pom padre.
FROM maven:3.9-eclipse-temurin-17

# Robot.createScreenCapture() (FrameCapturer) necesita un entorno grafico.
# El contenedor no tiene pantalla real, asi que se usa Xvfb (framebuffer X
# virtual) para evitar HeadlessException / falta de DISPLAY. No cambia nada
# del codigo Java, solo envuelve la ejecucion.
RUN apt-get update \
    && apt-get install -y --no-install-recommends xvfb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service camera-feed-service
COPY vision-detection-service/pom.xml vision-detection-service/pom.xml
COPY object-presence-tracker/pom.xml object-presence-tracker/pom.xml
COPY alert-engine/pom.xml alert-engine/pom.xml
COPY realtime-gateway/pom.xml realtime-gateway/pom.xml

# Compila en build-time para detectar errores temprano (no en cada arranque).
RUN mvn -q -pl camera-feed-service -am package -DskipTests

ENV DISPLAY=:99
EXPOSE 8080

# Reutiliza exec-maven-plugin, ya configurado en camera-feed-service/pom.xml
# (mainClass=FrameCapturerTest), igual que se corre localmente.
CMD ["xvfb-run", "-a", "mvn", "-pl", "camera-feed-service", "-am", "exec:java"]
