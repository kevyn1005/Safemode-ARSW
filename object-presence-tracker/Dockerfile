FROM maven:3.9-eclipse-temurin-17

WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service camera-feed-service
COPY vision-detection-service vision-detection-service
COPY object-presence-tracker object-presence-tracker
COPY alert-engine alert-engine
COPY realtime-gateway realtime-gateway

RUN mvn -q -pl object-presence-tracker -am install -DskipTests \
    && mvn -q -pl object-presence-tracker dependency:build-classpath -Dmdep.outputFile=/workspace/cp-tracker.txt

# El argumento 0 = correr hasta que lo detengan. Base, modelo y fuente de frames los pone docker-compose.
CMD ["sh", "-c", "exec java -cp object-presence-tracker/target/classes:$(cat cp-tracker.txt) com.safemode.presence.ObjectPresenceTrackerTest 0"]
