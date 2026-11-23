// Calculo de disponibilidad de Safemode con las formulas del requerimiento del Release 2:
//   serie      A_total = A1 x A2 x ... x An
//   paralelo   A_total = 1 - (1 - A1) x (1 - A2) x ... x (1 - An)
//   downtime mensual = (1 - A_total) x 30 dias      downtime anual = (1 - A_total) x 365 dias
// Uso:  node docs/disponibilidad/calcular-disponibilidad.mjs
// Los SLA son los publicados por AWS (ver docs/disponibilidad/arquitectura-de-disponibilidad.md, seccion "Fuentes").

const serie = (...a) => a.reduce((x, y) => x * y, 1);
const paralelo = (...a) => 1 - a.reduce((x, y) => x * (1 - y), 1);

const MIN_MES = 30 * 24 * 60;      // 43 200 minutos
const H_ANIO = 365 * 24;           // 8 760 horas

function fmtDuracionMin(min) {
  if (min * 60 < 1) return '< 1 s';
  if (min < 1) return `${(min * 60).toFixed(0)} s`;
  if (min < 120) return `${min.toFixed(1)} min`;
  return `${(min / 60).toFixed(1)} h`;
}

function nueves(a) {
  const n = -Math.log10(1 - a);
  return Number.isFinite(n) ? n.toFixed(2) : 'inf';
}

function fila(nombre, a) {
  const mesMin = (1 - a) * MIN_MES;
  const anioH = (1 - a) * H_ANIO;
  return `| ${nombre} | ${(a * 100).toFixed(4)} % | ${nueves(a)} | ${fmtDuracionMin(mesMin)} | ${anioH.toFixed(2)} h |`;
}

// --- SLA publicados por AWS (fraccion, no porcentaje) ----------------------------------------------------------------
const SLA = {
  ec2Instancia: 0.995,     // Compute SLA: nivel de instancia (una sola instancia EC2)
  ec2Region: 0.9999,       // Compute SLA: nivel de region (instancias en 2 o mas zonas de disponibilidad)
  elbMultiAz: 0.9999,      // Elastic Load Balancing SLA: balanceador desplegado en varias zonas
  rdsMultiAz: 0.9995,      // RDS SLA: instancia Multi-AZ
  route53: 1.0,            // Route 53: compromiso de 100 % (texto vigente por confirmar, ver el documento)
};

// --- Arquitecturas comparadas ----------------------------------------------------------------------------------------
// HOY: un solo servidor EC2 con Caddy, gateway, PostgreSQL, Grafana y tracker. Todo en serie dentro de una misma instancia:
// si la instancia cae, cae todo; no hay componentes independientes que se puedan sumar en paralelo.
const hoy = SLA.ec2Instancia;

// HOY con dos replicas del gateway en el MISMO servidor: el segundo contenedor no es redundancia, comparte la instancia.
const hoyConReplicasEnUnHost = SLA.ec2Instancia;

// OBJETIVO: ruta critica de una alerta = DNS -> balanceador -> capa de gateway (2 replicas, 2 zonas) -> base de datos.
const capaGateway = paralelo(SLA.ec2Instancia, SLA.ec2Instancia);              // 2 instancias en paralelo, cada una a 99.5 %
const objetivo = serie(SLA.route53, SLA.elbMultiAz, capaGateway, SLA.rdsMultiAz);

// Sensibilidad 1: la misma capa distribuida, pero con PostgreSQL en una sola instancia EC2 (sin Multi-AZ).
const objetivoBdUnica = serie(SLA.route53, SLA.elbMultiAz, capaGateway, SLA.ec2Instancia);

// Sensibilidad 2: usar el SLA regional de EC2 (99.99 %) para la capa de gateway en lugar del calculo en paralelo.
const objetivoSlaRegional = serie(SLA.route53, SLA.elbMultiAz, SLA.ec2Region, SLA.rdsMultiAz);

// Sensibilidad 3: tres replicas del gateway (el balanceador ya soporta N): casi no cambia, el limite es la base de datos.
const objetivoTresReplicas = serie(SLA.route53, SLA.elbMultiAz, paralelo(SLA.ec2Instancia, SLA.ec2Instancia, SLA.ec2Instancia), SLA.rdsMultiAz);

console.log('## Componentes y SLA usados\n');
console.log('| Componente | SLA publicado | Tipo en el calculo |');
console.log('|---|---|---|');
console.log(`| Servidor EC2 (instancia unica) | ${(SLA.ec2Instancia * 100).toFixed(2)} % | serie hoy; cada replica en paralelo en el objetivo |`);
console.log(`| EC2 a nivel de region (2+ zonas) | ${(SLA.ec2Region * 100).toFixed(2)} % | sensibilidad |`);
console.log(`| Elastic Load Balancing (Multi-AZ) | ${(SLA.elbMultiAz * 100).toFixed(2)} % | serie |`);
console.log(`| RDS PostgreSQL (Multi-AZ) | ${(SLA.rdsMultiAz * 100).toFixed(2)} % | serie |`);
console.log(`| Route 53 (DNS) | ${(SLA.route53 * 100).toFixed(2)} % | serie |`);
console.log('\n## Disponibilidad calculada\n');
console.log('| Arquitectura | Disponibilidad | Nueves | Downtime mensual | Downtime anual |');
console.log('|---|---|---|---|---|');
console.log(fila('Hoy: un servidor con todo', hoy));
console.log(fila('Hoy + 2 replicas en el MISMO servidor', hoyConReplicasEnUnHost));
console.log(fila('Objetivo: DNS -> ALB -> 2 gateways (2 zonas) -> RDS Multi-AZ', objetivo));
console.log(fila('  sensibilidad: capa de gateway con el SLA regional de EC2', objetivoSlaRegional));
console.log(fila('  sensibilidad: 3 replicas del gateway', objetivoTresReplicas));
console.log(fila('  sensibilidad: PostgreSQL en una sola instancia (sin Multi-AZ)', objetivoBdUnica));

console.log('\n## Capa de gateway sola\n');
console.log('| Configuracion | Disponibilidad | Downtime mensual |');
console.log('|---|---|---|');
for (const [n, a] of [['1 replica', SLA.ec2Instancia], ['2 replicas en paralelo', paralelo(SLA.ec2Instancia, SLA.ec2Instancia)],
  ['3 replicas en paralelo', paralelo(SLA.ec2Instancia, SLA.ec2Instancia, SLA.ec2Instancia)]]) {
  console.log(`| ${n} | ${(a * 100).toFixed(5)} % | ${fmtDuracionMin((1 - a) * MIN_MES)} |`);
}

console.log('\n## Cuanto aporta cada componente a la indisponibilidad del objetivo\n');
const parte = (nombre, a) => ({ nombre, indisp: 1 - a });
const partes = [parte('DNS', SLA.route53), parte('Balanceador', SLA.elbMultiAz), parte('Capa de gateway (2 replicas)', capaGateway), parte('Base de datos (Multi-AZ)', SLA.rdsMultiAz)];
const suma = partes.reduce((s, p) => s + p.indisp, 0);
console.log('| Componente | Indisponibilidad | Peso aproximado |');
console.log('|---|---|---|');
for (const p of partes) {
  console.log(`| ${p.nombre} | ${(p.indisp * 100).toFixed(4)} % | ${((p.indisp / suma) * 100).toFixed(0)} % |`);
}

// --- Contraste con lo observado en el prototipo ---------------------------------------------------------------------------
// Datos de docs/disponibilidad/evidencias/escalado-gateway.txt (se actualizan a mano al repetir la prueba).
const observadas = Number(process.env.PETICIONES || 0);
const fallidas = Number(process.env.FALLIDAS || 0);
if (observadas > 0) {
  const disp = (observadas - fallidas) / observadas;
  // Regla de los tres: con 0 fallos en n intentos, la tasa de fallo es < 3/n con 95 % de confianza.
  const cotaFallo = fallidas === 0 ? 3 / observadas : null;
  console.log('\n## Contraste con el prototipo\n');
  console.log(`Peticiones observadas: ${observadas}; fallidas: ${fallidas}; disponibilidad observada: ${(disp * 100).toFixed(3)} %`);
  if (cotaFallo !== null) {
    console.log(`Con 0 fallos en ${observadas} peticiones solo se puede afirmar (95 % de confianza) una disponibilidad de al menos ${((1 - cotaFallo) * 100).toFixed(2)} %: la muestra es demasiado corta para medir nueves.`);
  }
}
