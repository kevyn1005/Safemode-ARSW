FROM maven:3.9-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends xvfb libxtst6 libxi6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service camera-feed-service
COPY vision-detection-service vision-detection-service
COPY object-presence-tracker/pom.xml object-presence-tracker/pom.xml
COPY alert-engine/pom.xml alert-engine/pom.xml
COPY realtime-gateway/pom.xml realtime-gateway/pom.xml

# camera-feed-service se instala en el repositorio local (no basta con
# "package") para que vision-detection-service lo resuelva como una
# dependencia normal en el siguiente comando mvn (proceso aparte, no
# comparte el cache del reactor de la corrida anterior).
RUN mvn -q -N install \
    && mvn -q -pl camera-feed-service install -DskipTests \
    && mvn -q -pl vision-detection-service package -DskipTests \
    && mvn -q -pl vision-detection-service dependency:build-classpath -Dmdep.outputFile=/workspace/cp-vision-detection-service.txt

ENV DISPLAY=:99
EXPOSE 8080

# Mismo motivo que camera-feed-service/Dockerfile: nada de xvfb-run ni de
# "mvn exec:java" (correria sobre todo el reactor). Se arma el classpath
# en build-time con dependency:build-classpath (incluye el jar de
# camera-feed-service y el de onnxruntime) y se corre con java directo.
CMD ["sh", "-c", "Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp & sleep 1; exec java -cp vision-detection-service/target/classes:$(cat cp-vision-detection-service.txt) com.safemode.vision.VisionDetectionTest"]
