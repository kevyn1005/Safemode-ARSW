# Apagar y encender la instancia EC2 sin perder nada (ni la demo)

Para quien administra el laboratorio de AWS (Juli). Sirve para ahorrar créditos entre ensayos. **Detener no es terminar**: detener conserva el
disco (los datos de PostgreSQL, Grafana, el repositorio y el `.env`); **terminar lo borra todo**.

## Antes de apagar

1. **Que el último cambio ya esté desplegado.** El pipeline despliega por SSH a la instancia en cada push a `develop`, y **necesita la instancia encendida**: si se
   apaga antes de fusionar un PR, el job *Deploy* falla y esos cambios no llegan hasta encenderla y volver a ejecutar el workflow.
2. **Comprobar que lo que se desplegó funciona**: `powershell -ExecutionPolicy Bypass -File docs\despliegue\verificar-despliegue.ps1` (solo peticiones públicas de lectura).
   Si `/grafana/` da `302` en vez de `401`, Caddy sigue con la configuración vieja (un archivo reemplazado por `git checkout` no se ve dentro del contenedor):
   en la instancia, `docker compose -f docker-compose.yml -f docker-compose.persistencia.yml -f docker-compose.tracker.yml -f docker-compose.publico.yml restart caddy`.
3. **Respaldo mínimo** (por si el laboratorio se deshabilita, que borra los recursos): copiar fuera de la instancia el `.env` y un volcado de la base:
   `docker compose ... exec -T postgres pg_dump -U safemode safemode > safemode-respaldo.sql`. El código está en GitHub y el stack se levanta igual en cualquier computador.
4. Anotar la **IP pública actual** y el tipo de instancia.

## Apagar

En la consola de EC2: seleccionar la instancia → **Estado de la instancia → Detener** (*Stop*). **Nunca «Terminar»**.
El laboratorio también detiene las instancias solo cuando vence la sesión (unas 4 horas); se extiende con *Start Lab*.

## Encender (hacerlo con 2 o 3 horas de margen antes de una demostración)

1. Iniciar la sesión del laboratorio (*Start Lab*) y **iniciar** la instancia.
2. **Mirar la IP pública nueva**: cambia en cada encendido, salvo que tenga una IP elástica. Actualizar DuckDNS con esa IP (desde la cuenta de DuckDNS; el token no se
   comparte en chats ni en el repositorio). Sin este paso el dominio no llega al servidor.
3. Esperar 1 o 2 minutos y comprobar que los contenedores subieron solos (`restart: unless-stopped`):
   `docker ps` por SSH. Si falta alguno: `docker compose -f docker-compose.yml -f docker-compose.persistencia.yml -f docker-compose.tracker.yml -f docker-compose.publico.yml up -d`.
4. `verificar-despliegue.ps1` otra vez: todo en `OK`.
5. A mano, con sesión: entrar con Google, ver el panel, recibir una alerta y abrir Grafana (pide usuario y clave).

## Si algo no sube

- El dominio no resuelve o apunta a la IP vieja → DuckDNS sin actualizar (paso 2).
- El certificado HTTPS falla → Caddy necesita que el dominio apunte a la instancia para renovarlo; esperar unos minutos tras actualizar DuckDNS.
- El panel carga pero no hay alertas → revisar `docker ps` (gateway, postgres) y `docker logs` del gateway.
- Plan B para la demostración: el stack completo corre en un computador (`docker compose -f docker-compose.yml -f docker-compose.persistencia.yml up -d --build ...`, ver `.env.example`).
