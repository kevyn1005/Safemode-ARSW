# Servidor de base de datos H2 (modo TCP): lo comparten el tracker (que corre en el computador, porque captura la
# pantalla real) y el realtime-gateway (que corre en su contenedor). Ver docs/adr/0002-docker-compose-gateway-y-base-de-datos.md
FROM eclipse-temurin:17-jre

# Mismo jar (2.2.224) que usa el proyecto en Maven; el checksum garantiza que lo descargado es el archivo esperado.
ADD --checksum=sha256:b9d8f19358ada82a4f6eb5b174c6cfe320a375b5a9cb5a4fe456d623e6e55497 \
    https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar /opt/h2/h2.jar

# La base vive en este volumen (docker-compose monta un volumen con nombre aqui): sobrevive a reiniciar el contenedor.
VOLUME /data
EXPOSE 9092

# -tcpAllowOthers: acepta conexiones de otros contenedores (el puerto se publica solo en 127.0.0.1, ver docker-compose.yml).
# -ifNotExists: deja crear la base "presence" la primera vez.
CMD ["java", "-cp", "/opt/h2/h2.jar", "org.h2.tools.Server", "-tcp", "-tcpAllowOthers", "-tcpPort", "9092", "-ifNotExists", "-baseDir", "/data"]
