"""Genera el CSV para importar a Azure Boards (Boards > Work items > Import Work Items) a partir de docs/backlog.md.

    python docs/azure-devops/generar-csv.py

Salida: docs/azure-devops/importar-backlog.csv (UTF-8 con BOM). No inventa nada: titulos, tamanos, criterios Gherkin, componente y evidencia salen de
docs/backlog.md; el sprint se asigna por la semana en la que cayo el trabajo (los commits de Git), con la regla "fin de semana -> sprint de la semana
siguiente"; las historias de la epica Release 2 salen de los documentos de docs/ y de los commits.
"""
import csv
import html
import os
import re

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
BACKLOG = os.path.join(RAIZ, 'docs', 'backlog.md')
SALIDA = os.path.join(RAIZ, 'docs', 'azure-devops', 'importar-backlog.csv')

NOTA = ('Registrado en Azure DevOps el 20-nov-2026 a partir de docs/backlog.md y del historial de Git: el seguimiento diario se llevó en el repositorio. '
        'La fecha real de entrega es la de la evidencia citada, no la fecha de cierre de este elemento.')

# Sprints de Azure DevOps (lunes a viernes). Los sprints 6 a 10 se crean en el paso 2 del README.
SPRINTS = {
    1: ('7-12 sep', '2026-09-07', '2026-09-12'),
    2: ('21-25 sep', '2026-09-21', '2026-09-25'),
    3: ('28 sep-2 oct', '2026-09-28', '2026-10-02'),
    4: ('5-9 oct', '2026-10-05', '2026-10-09'),
    5: ('12-16 oct', '2026-10-12', '2026-10-16'),
    6: ('19-23 oct', '2026-10-19', '2026-10-23'),
    7: ('26-30 oct', '2026-10-26', '2026-10-30'),
    8: ('2-6 nov', '2026-11-02', '2026-11-06'),
    9: ('9-13 nov', '2026-11-09', '2026-11-13'),
    10: ('16-20 nov', '2026-11-16', '2026-11-20'),
}

# HU del backlog -> sprint de Azure DevOps (por la fecha de su evidencia). HU-01 a HU-04 ya estan en Azure como HU-01 y HU-02 (ver README).
SPRINT_DE_HU = {
    5: 2, 6: 2, 7: 2, 8: 2, 9: 2, 10: 2, 11: 2, 13: 2, 14: 2, 15: 2, 16: 2, 17: 2, 18: 2, 35: 2,
    36: 3,
    12: 5, 19: 5, 21: 5, 22: 5, 23: 5, 24: 5, 25: 5, 26: 5, 27: 5, 29: 5, 30: 5, 33: 5, 37: 5, 38: 5,
    34: 10,
}
ESTADO = {'Hecho': 'Closed', 'Parcial': 'Active', 'Pendiente': 'New'}
EPICAS_EXISTENTES = {'E1'}  # ya existe en Azure (id 1)


def parsear_backlog():
    texto = open(BACKLOG, encoding='utf-8').read().replace('\r\n', '\n')
    # tabla resumen
    filas, epica, feature = [], None, None
    for linea in texto.split('\n'):
        if not linea.startswith('|'):
            continue
        c = [x.strip() for x in linea.strip().strip('|').split('|')]
        if len(c) != 7 or c[0] in ('ID', '---'):
            continue
        if c[0] == '' and c[1].startswith('**E'):
            epica = c[1].strip('*')
            continue
        m = re.fullmatch(r'HU-(\d+)', c[0])
        if m:
            filas.append({'n': int(m.group(1)), 'epica': epica, 'feature': c[1], 'resumen': c[2], 'moscow': c[3], 'puntos': c[4],
                          'release': c[5], 'estado': c[6]})
    # detalle por HU
    detalle = {}
    for m in re.finditer(r'^\*\*HU-(\d+)\*\* · (.*?)(?=^\*\*HU-\d+\*\* ·|^#{2,4} |\Z)', texto, re.S | re.M):
        n, cuerpo = int(m.group(1)), m.group(2)
        primera, _, resto = cuerpo.partition('\n')
        gh = re.search(r'```gherkin\n(.*?)```', resto, re.S)
        comp = re.search(r'^(Componente:.*)$', resto, re.M)
        nota = re.findall(r'\*(?:Parcial|Pendiente)[^*]*\*', primera)
        detalle[n] = {'historia': re.sub(r'\*(Parcial|Pendiente)[^*]*\*', '', primera).strip(), 'gherkin': gh.group(1).strip() if gh else '',
                      'componente': comp.group(1).strip() if comp else '', 'nota': ' '.join(nota)}
    for f in filas:
        f.update(detalle.get(f['n'], {}))
    return filas


def h(texto):
    """Texto plano -> HTML seguro (el campo Description de Azure Boards es HTML)."""
    return html.escape(texto, quote=False).replace('**', '').replace('`', '').replace('\n', '<br>')


# ----- Release 2: epica E9 (observabilidad, persistencia, despliegue, disponibilidad, rendimiento, seguridad, mantenibilidad, documentos) -----
# (feature, titulo, sprint, puntos, estado, fecha real / evidencia, criterio de aceptacion)
RELEASE2 = [
    ('F9.1 Observabilidad', 'R2-01 · Ver tráfico, latencia, errores y entrega de alertas en Prometheus y Grafana', 7, 5, 'Closed',
     'Commits 2026-10-28 y 29 (Micrometer, métricas, Prometheus y Grafana en Compose), 2026-11-07 (un solo Prometheus y Grafana) y 2026-11-19 (tráfico por ruta). docs/persistencia/, performance/monitoring/',
     'Dado el stack encendido, cuando se abre Grafana, entonces se ven las solicitudes, la latencia y los errores por ruta, la entrega de alertas, los sockets y el estado de cada dependencia'),
    ('F9.1 Observabilidad', 'R2-02 · Recibir un aviso cuando la experiencia se degrade, no solo cuando falte CPU', 10, 3, 'Closed',
     'Commit 2026-11-20. performance/monitoring/reglas-alertas.yml (8 reglas); en el estrés se dispararon las de latencia y entrega y no la de CPU (docs/rendimiento/informe-k6.md)',
     'Dado un estrés de 600 operadores, cuando el P95 de entrega supera 2 s, entonces se dispara EntregaDeAlertasLenta aunque la CPU use menos de 1 núcleo'),
    ('F9.2 Persistencia', 'R2-03 · Guardar permisos, configuración del puesto y datos efímeros en el motor que garantiza cada cosa', 8, 8, 'Closed',
     'Commits 2026-11-06 y 07: access-service (PostgreSQL), collaboration-service (MongoDB y Redis), service-common. docs/persistencia/, ADR 0005',
     'Dado un operador sin permiso de escritura, cuando intenta cambiar las zonas, entonces recibe 403; con la misma versión de dos escrituras, una gana (200) y la otra recibe 409'),
    ('F9.2 Persistencia', 'R2-04 · Que el gateway siga funcionando cuando la base se reinicia', 9, 3, 'Closed',
     'Commit 2026-11-07 (ReconnectingConnection). docs/persistencia/ evidencia 16',
     'Dado el gateway en marcha, cuando se reinicia PostgreSQL, entonces el gateway reabre la conexión solo, sin reiniciarse'),
    ('F9.2 Persistencia', 'R2-05 · Ver los últimos cambios de la configuración del puesto', 9, 3, 'Closed',
     'Commits 2026-11-07 (colección puesto_history con cursor, índice único y retención de 90 días). docs/persistencia/ evidencia 19',
     'Dado un cambio de zonas, cuando se consulta el historial, entonces aparece con su versión y quien lo hizo'),
    ('F9.3 Despliegue y portabilidad', 'R2-06 · Tener el sistema funcionando en AWS con HTTPS y dominio público', 9, 8, 'Closed',
     'Commits 2026-11-13 (despliegue en AWS: tracker en contenedor, Caddy, dominio) y 2026-11-15 (reinicio automático). https://safemode-arsw.duckdns.org/, docs/despliegue/',
     'Dado el dominio público, cuando se abre por HTTPS, entonces responde el panel y las rutas protegidas piden sesión'),
    ('F9.3 Despliegue y portabilidad', 'R2-07 · Que cada cambio se compile, pruebe, mida y despliegue solo', 10, 8, 'Closed',
     'Commits 2026-10-13 (primer pipeline), 2026-11-16 (despliegue por SSH) y 2026-11-19 (workflows reutilizables con control de calidad). .github/workflows/',
     'Dado un push a develop, cuando termina el pipeline en verde, entonces se despliega por SSH; si el control de calidad falla, no se despliega'),
    ('F9.3 Despliegue y portabilidad', 'R2-08 · Poder mover el sistema entre computador, prototipo y AWS con los mismos archivos', 10, 3, 'Closed',
     'Commit 2026-11-19 (guía de apagar y encender la EC2 y verificar-despliegue.ps1). docker-compose*.yml, docs/despliegue/. Límite: la migración a otro servidor no se ejecutó como prueba',
     'Dado el repositorio, cuando se levanta con Compose en un computador, entonces corre el mismo stack que en AWS'),
    ('F9.4 Disponibilidad y escalabilidad', 'R2-09 · Seguir atendiendo cuando cae una réplica del gateway', 10, 8, 'Closed',
     'Commits 2026-11-19: gateway en varias réplicas y prototipo con balanceador. docs/disponibilidad/ (escenario ED-1): 0 de 555 peticiones fallidas, reconexión ≤ 2,1 s, 17/17 comprobaciones en 3 corridas',
     'Dado dos réplicas con operadores conectados, cuando se mata una réplica, entonces no falla ninguna petición, los operadores se reconectan en 2,1 s o menos y no se pierden ni duplican alertas'),
    ('F9.4 Disponibilidad y escalabilidad', 'R2-10 · Calcular la disponibilidad con los SLA publicados del proveedor', 10, 3, 'Closed',
     'Commit 2026-11-19. docs/disponibilidad/arquitectura-de-disponibilidad.md: 99,5 % (un servidor) a 99,9375 % (diseño distribuido); 3,6 h a 27 min de caída al mes',
     'Dado el diseño distribuido, cuando se aplican las fórmulas de serie y paralelo, entonces el resultado es 99,9375 % con su caída mensual y anual'),
    ('F9.4 Disponibilidad y escalabilidad', 'R2-11 · Desplegar el diseño distribuido en varios servidores', None, 8, 'New',
     'Pendiente: el despliegue público es de un solo servidor; el diseño está calculado y validado como prototipo (decisión del equipo)',
     'Dado el balanceador, cuando una zona cae, entonces las réplicas de la otra zona atienden'),
    ('F9.5 Rendimiento', 'R2-12 · Medir carga, estrés y pico con k6 (TPS, TPM, P95 y errores)', 10, 8, 'Closed',
     'Commits 2026-11-20. docs/rendimiento/informe-k6.md y performance/: meseta HTTP de 34-48 a 570-678 req/s; P95 del pico de 10,3 s a 0,6 s; 185.756 alertas entregadas',
     'Dado un puesto con 20 operadores, cuando corre la prueba de carga, entonces el P95 HTTP es menor de 500 ms y los errores menores de 1 %'),
    ('F9.5 Rendimiento', 'R2-13 · Que /report y el snapshot de alertas no hagan una consulta por fila', 10, 5, 'Closed',
     'Commit 2026-11-20. /report pasó de 1.892 ms a 6,3 ms (P95)',
     'Dado un reporte con 200 eventos, cuando se pide, entonces se resuelve con consultas por lotes y no con una por fila'),
    ('F9.6 Seguridad', 'R2-14 · ES-1: autenticar y autorizar a cada operador', 10, 5, 'Closed',
     'Commits 2026-10-12 (sesión con Google) y 2026-11-19. docs/seguridad/escenarios-de-seguridad.md: 35 pruebas, 100 % de rutas protegidas responden 401 sin sesión. Aprobado por el profesor',
     'Dado una petición sin sesión, cuando llama a una ruta protegida, entonces recibe 401; con un correo fuera de la lista, no entra'),
    ('F9.6 Seguridad', 'R2-15 · ES-2: resistir ataques web y cerrar la sesión de verdad', 10, 5, 'Closed',
     'Commits 2026-11-19. docs/seguridad/: Origin y Host en todo cambio de estado, pool de hilos con límites de tiempo, WebSocket cerrado al terminar la sesión. Aprobado por el profesor',
     'Dado un origen ajeno, cuando intenta un cambio de estado, entonces se rechaza; al vencer la sesión se cierran sus WebSockets'),
    ('F9.6 Seguridad', 'R2-16 · ES-3: proteger la integridad del canal /frames y de Grafana', 10, 5, 'Closed',
     'Commits 2026-11-19 (forward_auth en Caddy). docs/seguridad/: 12 de 12 comprobaciones. Aprobado por el profesor. Brechas declaradas: cookie sin Secure y sin registro de auditoría',
     'Dado un cliente sin sesión, cuando abre /frames o /grafana, entonces el proxy lo rechaza'),
    ('F9.7 Mantenibilidad', 'R2-17 · Medir la calidad con SonarQube y cumplir cobertura de 80 % en backend y panel', 10, 8, 'Closed',
     'Commits 2026-11-06 (Sonar) y 2026-11-19 y 20 (cobertura y correcciones). docs/mantenibilidad/: control de calidad en verde, A/A/A, 0 errores y 0 vulnerabilidades, backend 81,8 %, panel 99,3 %, 479 pruebas de Java y 144 de Node',
     'Dado un push, cuando corre el análisis, entonces el control de calidad está en verde y la cobertura es de 80 % o más en cada capa'),
    ('F9.7 Mantenibilidad', 'R2-18 · Correr el análisis en SonarCloud con la cuenta del equipo', None, 3, 'New',
     'Pendiente: requiere la cuenta, el token y las variables SONAR_ORGANIZATION y SONAR_PROJECT_KEY. Hoy se mide con un SonarQube local con la misma configuración',
     'Dado el repositorio, cuando corre el pipeline, entonces el resultado aparece en SonarCloud'),
    ('F9.8 Documentación', 'R2-19 · Documento de arquitectura en LaTeX con 17 vistas ArchiMate', 10, 8, 'Closed',
     'Commits 2026-11-20. docs/arquitectura/safemode-arquitectura.pdf (65 páginas, 11 capítulos) y modelo docs/diagramas/safemode-arquitectura.archimate',
     'Dado el repositorio, cuando se ejecuta compilar.ps1, entonces se genera el PDF de 11 capítulos'),
    ('F9.8 Documentación', 'R2-20 · Artículo académico en formato IEEE', 10, 5, 'Closed',
     'Commit 2026-11-20. docs/articulo/articulo.pdf (5 páginas, IEEEtran)',
     'Dado el artículo, cuando se compila, entonces tiene las secciones pedidas y 4 a 6 páginas'),
    ('F9.8 Documentación', 'R2-21 · Backlog, plan de releases y guía de sustentación al día con lo construido', 10, 3, 'Closed',
     'docs/backlog.md, docs/release-plan.md y docs/sustentacion.md (20-nov-2026)',
     'Dado lo construido, cuando se revisan los documentos, entonces coinciden con las pruebas y las mediciones'),
]

COLUMNAS = ['ID', 'Work Item Type', 'Title 1', 'Title 2', 'Title 3', 'State', 'Iteration Path', 'Story Points', 'Tags', 'Description', 'Acceptance Criteria']


def ruta_sprint(n):
    return 'SafeMode' if n is None else f'SafeMode\\Sprint {n}'


def main():
    hus = [f for f in parsear_backlog() if f['n'] >= 5]  # HU-01 a HU-04 ya estan en Azure
    filas = []

    def fila(tipo, nivel, titulo, estado, sprint, puntos='', tags='', desc='', criterios=''):
        f = {c: '' for c in COLUMNAS}
        f['Work Item Type'] = tipo
        f[f'Title {nivel}'] = titulo
        # Azure Boards no deja CREAR elementos en Closed/Active: se importan en New con una etiqueta y se pasan a su estado real con una
        # edicion en bloque (consulta por etiqueta). La etiqueta se queda como filtro: "hecho" = Closed, "parcial" = Active.
        marca = {'Closed': 'hecho', 'Active': 'parcial'}.get(estado)
        f['State'], f['Iteration Path'], f['Story Points'] = 'New', ruta_sprint(sprint), puntos
        f['Tags'] = tags + (';' + marca if marca else '')
        f['Description'], f['Acceptance Criteria'] = desc, criterios
        filas.append(f)

    # ---- epicas E2..E8 del backlog ----
    epicas = {}
    for hu in hus:
        epicas.setdefault(hu['epica'], {}).setdefault(hu['feature'].split(' ')[0], []).append(hu)  # agrupa por codigo (F2.1)

    def estado_grupo(estados):
        if all(e == 'Closed' for e in estados):
            return 'Closed'
        if all(e == 'New' for e in estados):
            return 'New'
        return 'Active'

    for epica, features in epicas.items():
        estados_epica = [ESTADO[hu['estado']] for fs in features.values() for hu in fs]
        sprints_epica = [SPRINT_DE_HU.get(hu['n']) for fs in features.values() for hu in fs if SPRINT_DE_HU.get(hu['n'])]
        fila('Epic', 1, epica, estado_grupo(estados_epica), min(sprints_epica) if sprints_epica else None, tags='backlog;registrado-retroactivo',
             desc=h(f'Épica del backlog de producto (docs/backlog.md). {NOTA}'))
        for feat, fs in features.items():
            # el nombre de la feature en la tabla es "F2.1 Retiro del objeto"; las HU siguientes solo repiten "F2.1"
            nombre = next((hu['feature'] for hu in fs if len(hu['feature'].split(' ')) > 1), feat)
            sp = [SPRINT_DE_HU.get(hu['n']) for hu in fs if SPRINT_DE_HU.get(hu['n'])]
            fila('Feature', 2, nombre, estado_grupo([ESTADO[hu['estado']] for hu in fs]), min(sp) if sp else None, tags='backlog;registrado-retroactivo',
                 desc=h(f'Feature del backlog de producto. {NOTA}'))
            for hu in fs:
                sprint = SPRINT_DE_HU.get(hu['n'])
                partes = [hu['historia'], '', f"Prioridad MoSCoW: {hu['moscow']} · Tamaño: {hu['puntos']} · Release y sprint del plan: {hu['release']} · Estado: {hu['estado']}"]
                if sprint:
                    partes.append(f"Sprint de Azure DevOps: Sprint {sprint} ({SPRINTS[sprint][0]} de 2026), asignado por la semana en que se hizo el trabajo.")
                if hu.get('componente'):
                    partes += ['', hu['componente']]
                if hu.get('nota'):
                    partes += ['', hu['nota'].replace('*', '')]
                partes += ['', NOTA]
                tags = ';'.join(['backlog', hu['moscow'], hu['release'].split(' ')[0] if hu['release'][0] == 'R' else 'siguiente', 'registrado-retroactivo'])
                criterio = hu['gherkin']
                if not criterio and 'Mismo criterio' in hu['historia']:
                    criterio = hu['historia'][hu['historia'].index('Mismo criterio'):]
                fila('User Story', 3, f"HU-{hu['n']:02d}: {hu['resumen']}", ESTADO[hu['estado']], sprint, hu['puntos'], tags, h('\n'.join(partes)), h(criterio))

    # ---- epica E9 Release 2 ----
    estados9 = [r[4] for r in RELEASE2]
    fila('Epic', 1, 'E9 · Release 2: calidad, persistencia, despliegue y documentación', estado_grupo(estados9), 7, tags='release-2;registrado-retroactivo',
         desc=h('Entrega final del curso: observabilidad, persistencia, despliegue en AWS, disponibilidad y escalabilidad, rendimiento (k6), seguridad, mantenibilidad, portabilidad y documentos. '
                'Cada historia cita su evidencia (commits, informes y pruebas). ' + NOTA))
    por_feature = {}
    for r in RELEASE2:
        por_feature.setdefault(r[0], []).append(r)
    for feat, rs in por_feature.items():
        sp = [r[2] for r in rs if r[2]]
        fila('Feature', 2, feat, estado_grupo([r[4] for r in rs]), min(sp) if sp else None, tags='release-2;registrado-retroactivo', desc=h(f'Feature del Release 2. {NOTA}'))
        for _, titulo, sprint, puntos, estado, evidencia, criterio in rs:
            partes = [f'Evidencia y fecha real: {evidencia}']
            if sprint:
                partes.append(f'Sprint de Azure DevOps: Sprint {sprint} ({SPRINTS[sprint][0]} de 2026), asignado por la semana en que se hizo el trabajo.')
            partes += ['', NOTA]
            fila('User Story', 3, titulo, estado, sprint, puntos, 'release-2;registrado-retroactivo', h('\n'.join(partes)), h(criterio))

    with open(SALIDA, 'w', encoding='utf-8-sig', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNAS, quoting=csv.QUOTE_ALL)
        w.writeheader()
        w.writerows(filas)

    tipos = {}
    for f in filas:
        tipos[f['Work Item Type']] = tipos.get(f['Work Item Type'], 0) + 1
    por_sprint, por_estado = {}, {}
    for f in filas:
        if f['Work Item Type'] == 'User Story':
            por_sprint[f['Iteration Path']] = por_sprint.get(f['Iteration Path'], 0) + 1
            final = 'Closed' if 'hecho' in f['Tags'].split(';') else 'Active' if 'parcial' in f['Tags'].split(';') else 'New'
            por_estado[final] = por_estado.get(final, 0) + 1
    print('filas:', len(filas), tipos)
    print('historias por sprint:', dict(sorted(por_sprint.items())))
    print('historias por estado:', por_estado)
    print('escrito:', SALIDA)


if __name__ == '__main__':
    main()
