FROM maven:3.9-eclipse-temurin-17

WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service camera-feed-service
COPY vision-detection-service vision-detection-service
COPY object-presence-tracker object-presence-tracker
COPY alert-engine alert-engine
COPY realtime-gateway realtime-gateway
# la pagina del panel; el gateway la entrega en http://localhost:8080
COPY security-dashboard/index.html security-dashboard/index.html

# realtime-gateway depende de alert-engine, que depende del tracker y este del modulo de vision: con -am se construyen e
# instalan todos en el repositorio local (el pom padre incluido). Igual que en los otros Dockerfiles, el classpath se arma
# en build-time y se corre con java directo (nada de "mvn exec:java" en runtime, ver docs/adr/0001-...md).
RUN mvn -q -pl realtime-gateway -am install -DskipTests \
    && mvn -q -pl realtime-gateway dependency:build-classpath -Dmdep.outputFile=/workspace/cp-realtime-gateway.txt

# 0.0.0.0: dentro del contenedor hay que escuchar en todas las interfaces para que el puerto publicado llegue.
ENV SAFEMODE_BIND_HOST=0.0.0.0
# 8080 = pagina y fotos (HTTP), 8090 = alertas en tiempo real (WebSocket)
EXPOSE 8080 8090

# El argumento 0 = correr hasta que lo detengan. La base (SAFEMODE_DB_URL) y las fotos (volumen) las pone docker-compose.
CMD ["sh", "-c", "exec java -cp realtime-gateway/target/classes:$(cat cp-realtime-gateway.txt) com.safemode.gateway.GatewayRunner 0"]
