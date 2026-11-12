-- Demostracion de las garantias de PostgreSQL (ADR 0005, bloque A de la matriz).
-- Ejecutar con el stack arrancado (la tabla alert la crea el gateway al iniciar):
--   docker compose -f docker-compose.yml -f docker-compose.persistencia.yml exec -T postgres psql -U safemode -d safemode -f /lab/evidence.sql
-- Los SEIS "ERROR" que veras estan PREVISTOS: son el motor rechazando datos invalidos dentro de transacciones que se revierten.
-- Nada de esto cambia los datos; no se elimina ninguna restriccion para que los errores desaparezcan.

\echo '== 1. ROLLBACK: una revocacion a medias se deshace (permiso y auditoria cambian juntos o no cambian)'
SELECT 'ANTES' AS etapa, operator_id, puesto_id, role, can_configure FROM membership WHERE operator_id = 'luis';
SELECT 'ANTES' AS etapa, COUNT(*) AS filas_de_auditoria FROM permission_audit;
BEGIN;
UPDATE membership SET can_configure = false WHERE puesto_id = 'puesto-1' AND operator_id = 'luis';
INSERT INTO permission_audit (operator_id, puesto_id, action) VALUES ('luis', 'puesto-1', 'revoke');
SELECT 'DENTRO DE LA TRANSACCION' AS etapa, operator_id, can_configure FROM membership WHERE operator_id = 'luis';
ROLLBACK;
SELECT 'DESPUES DEL ROLLBACK' AS etapa, operator_id, can_configure FROM membership WHERE operator_id = 'luis';
SELECT 'DESPUES DEL ROLLBACK' AS etapa, COUNT(*) AS filas_de_auditoria FROM permission_audit;

\echo '== 2. CLAVE PRIMARIA: una membresia no se repite (ERROR esperado)'
BEGIN;
INSERT INTO membership (operator_id, puesto_id, role, can_configure) VALUES ('ana', 'puesto-1', 'admin', true);
ROLLBACK;

\echo '== 3. CLAVE FORANEA: no hay membresia de un operador que no existe (ERROR esperado)'
BEGIN;
INSERT INTO membership (operator_id, puesto_id, role, can_configure) VALUES ('operador-inexistente', 'puesto-1', 'viewer', false);
ROLLBACK;

\echo '== 4. CLAVE FORANEA: ni de un puesto que no existe (ERROR esperado)'
BEGIN;
INSERT INTO membership (operator_id, puesto_id, role, can_configure) VALUES ('luis', 'puesto-inexistente', 'viewer', false);
ROLLBACK;

\echo '== 5. CHECK: el rol es uno de los tres definidos (ERROR esperado)'
BEGIN;
UPDATE membership SET role = 'superusuario' WHERE operator_id = 'luis';
ROLLBACK;

\echo '== 6. UNIQUE: un correo pertenece a un solo operador (ERROR esperado)'
BEGIN;
INSERT INTO operator (id, email, display_name) VALUES ('ana2', 'ana@safemode.test', 'Ana bis');
ROLLBACK;

\echo '== 7. UNIQUE de las alertas (HU-14): cada evento genera UNA sola alerta (ERROR esperado en la segunda insercion)'
BEGIN;
INSERT INTO alert (event_id, event_occurred_at, alert_type, severity, class_name, message, created_at)
VALUES (900001, TIMESTAMPTZ '2026-11-06 10:00:00-05', 'POSSIBLE_THEFT', 'HIGH', 'suitcase', 'prueba SQL', now());
INSERT INTO alert (event_id, event_occurred_at, alert_type, severity, class_name, message, created_at)
VALUES (900001, TIMESTAMPTZ '2026-11-06 10:00:00-05', 'POSSIBLE_THEFT', 'HIGH', 'suitcase', 'prueba SQL repetida', now());
ROLLBACK;
SELECT 'ALERTAS DE PRUEBA QUE QUEDARON (debe ser 0)' AS etapa, COUNT(*) FROM alert WHERE event_id = 900001;
