# Gestion de acceso (PostgreSQL): la autoridad de permisos. Ver docs/adr/0005-persistencia-consistencia-observabilidad.md
# Se construye desde la raiz del repo: docker compose -f docker-compose.yml -f docker-compose.persistencia.yml build access-service

# --- Etapa 1: compilar. Solo se copian los pom de los otros modulos (Maven los necesita para leer el reactor) y las fuentes de este.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service/pom.xml camera-feed-service/pom.xml
COPY vision-detection-service/pom.xml vision-detection-service/pom.xml
COPY object-presence-tracker/pom.xml object-presence-tracker/pom.xml
COPY alert-engine/pom.xml alert-engine/pom.xml
COPY realtime-gateway/pom.xml realtime-gateway/pom.xml
COPY collaboration-service/pom.xml collaboration-service/pom.xml
COPY service-common service-common
COPY access-service access-service
# El classpath se arma al construir (copy-dependencies) y se corre con java directo, igual que en los otros Dockerfiles
# (ADR 0001): nada de "mvn exec:java" en ejecucion. La cache de Maven acelera las reconstrucciones.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl access-service -am package dependency:copy-dependencies -DskipTests -DoutputDirectory=/app/lib \
    && cp access-service/target/access-service-1.0.0-SNAPSHOT.jar /app/lib/

# --- Etapa 2: ejecutar solo con el JRE (la imagen final pesa una fraccion de la de Maven)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/lib /app/lib
ENV PORT=8100 BIND_HOST=0.0.0.0
EXPOSE 8100
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=10s \
    CMD curl -fsS http://localhost:8100/health || exit 1
CMD ["java", "-XX:MaxRAMPercentage=60", "-cp", "/app/lib/*", "com.safemode.access.AccessServiceMain"]
