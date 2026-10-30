FROM maven:3.9-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends xvfb libxtst6 libxi6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service camera-feed-service
COPY vision-detection-service/pom.xml vision-detection-service/pom.xml
COPY object-presence-tracker/pom.xml object-presence-tracker/pom.xml
COPY alert-engine/pom.xml alert-engine/pom.xml
COPY realtime-gateway/pom.xml realtime-gateway/pom.xml

RUN mvn -q -pl camera-feed-service package -DskipTests

ENV DISPLAY=:99
EXPOSE 8080

# xvfb-run se quedaba colgado esperando a que el X server "avisara" que
# estaba listo y nunca llegaba a lanzar el comando (probado en vivo: el
# proceso Xvfb arrancaba bien, pero xvfb-run nunca hacia exec al comando).
# Por eso se levanta Xvfb a mano en background y se corre el jar directo.
#
# Tampoco se usa "mvn exec:java": con -am (necesario para que el reactor
# resuelva el pom padre) el goal se ejecuta sobre TODOS los proyectos del
# reactor, incluido el pom padre, que no tiene mainClass configurado y
# hace fallar el build antes de llegar a camera-feed-service (probado en
# vivo, ver docs/adr/0001-...md).
CMD ["sh", "-c", "Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp & sleep 1; exec java -cp camera-feed-service/target/classes com.safemode.camerafeed.FrameCapturerTest"]
