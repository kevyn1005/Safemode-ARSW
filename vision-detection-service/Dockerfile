# Build con contexto en la raiz del repo (ver docker-compose.yml): este
# modulo depende de camera-feed-service (dependencia Maven entre modulos
# del mismo reactor).
FROM maven:3.9-eclipse-temurin-17

# VisionDetectionTest usa FrameCapturer (Robot) internamente, por eso
# tambien necesita Xvfb. Ver Dockerfile de camera-feed-service.
RUN apt-get update \
    && apt-get install -y --no-install-recommends xvfb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY pom.xml .
COPY camera-feed-service camera-feed-service
COPY vision-detection-service vision-detection-service
COPY object-presence-tracker/pom.xml object-presence-tracker/pom.xml
COPY alert-engine/pom.xml alert-engine/pom.xml
COPY realtime-gateway/pom.xml realtime-gateway/pom.xml

RUN mvn -q -pl vision-detection-service -am package -DskipTests

ENV DISPLAY=:99
EXPOSE 8080

# vision-detection-service/pom.xml no tiene exec-maven-plugin configurado
# (a diferencia de camera-feed-service), asi que se invoca el goal
# completo sin tocar el pom.
CMD ["xvfb-run", "-a", "mvn", "-pl", "vision-detection-service", "-am", "org.codehaus.mojo:exec-maven-plugin:3.2.0:java", "-Dexec.mainClass=com.safemode.vision.VisionDetectionTest", "-Dexec.classpathScope=compile"]
