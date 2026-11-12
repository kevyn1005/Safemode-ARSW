# Colaboracion del panel (MongoDB + Redis). Ver docs/adr/0005-persistencia-consistencia-observabilidad.md
# Se construye desde la raiz del repo: docker compose -f docker-compose.yml -f docker-compose.persistencia.yml build collaboration-service

# --- Etapa 1: compilar (ver access-service/Dockerfile: mismo patron)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service/pom.xml camera-feed-service/pom.xml
COPY vision-detection-service/pom.xml vision-detection-service/pom.xml
COPY object-presence-tracker/pom.xml object-presence-tracker/pom.xml
COPY alert-engine/pom.xml alert-engine/pom.xml
COPY realtime-gateway/pom.xml realtime-gateway/pom.xml
COPY access-service/pom.xml access-service/pom.xml
COPY service-common service-common
COPY collaboration-service collaboration-service
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl collaboration-service -am package dependency:copy-dependencies -DskipTests -DoutputDirectory=/app/lib \
    && cp collaboration-service/target/collaboration-service-1.0.0-SNAPSHOT.jar /app/lib/

# --- Etapa 2: ejecutar solo con el JRE
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/lib /app/lib
ENV PORT=8110 BIND_HOST=0.0.0.0
EXPOSE 8110
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=10s \
    CMD curl -fsS http://localhost:8110/health || exit 1
CMD ["java", "-XX:MaxRAMPercentage=60", "-cp", "/app/lib/*", "com.safemode.collab.CollaborationMain"]
