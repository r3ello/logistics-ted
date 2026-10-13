# Análisis: `Персонал_Мастър.xlsx` (personal / brigadas)

Fecha: 2026-10-08. Archivo: `examples/Персонал_Мастър.xlsx`, una hoja `Персонал`, 89 personas,
17 columnas, sin imágenes incrustadas. El cliente lo enviará como CSV para importar.

**Hallazgo principal:** el seed de `V1__consolidated_baseline.sql` (workers 411–487, crews 102–128)
salió de una versión anterior de esta misma hoja: mismos nombres, mismas brigadas, mismo orden. O
sea que esto no es una carga inicial, es una **sincronización contra datos que ya existen** y que
tienen historia colgando (asistencias en `work_session`, logins de trabajador, etapas asignadas).

> **Verificado en vivo el 2026-10-08** contra `tedhouse_logistic` vía la API de `:8084` (solo GETs),
> ver §4b. Para prod (`tedhouse`) quedan las consultas de `staff-live-check.sql`.

## 1. Columnas

| Col | Cabecera | Significado (cliente) | Filas útiles | Nota |
|---|---|---|---|---|
| A | `Група` | Grupo/etapa | 89/89 | 10 valores; `Плочки ` lleva espacio final |
| B | `Бригада Име` | Nombre de brigada | 89/89 | **Apodo del jefe** (`Влади`, `Слави Р`), no único entre grupos |
| C | `Име Служител` | Nombre completo | 89/89 | 2 filas son `1`, `2` (personas sin nombre) |
| D | `Роля` | Rol | 89/89 | `Главен` (30) / `Служител` (59) |
| E | `Вид служител` | Tipo | 87/89 | **3 valores, no 2**: `Вътрешен` 69, `Външен` 10, `Подизпълнител` 8 |
| F | `Телефон` | Teléfono | 78/89 | Formato local `08xxxxxxxx`; `няма` = no tiene |
| G | `Имейл` | Email | 62/89 | Ensuciado con notas: `няма`, `чакам`, `Иска да говори с Теодор…` |
| H | `снимки` | Foto | 26/89 | **No son fotos**: enlaces a carpetas de Google Drive (1 es un fichero). Solo jefes |
| I | `Локация` | Origen (desplazamiento diario) | **2/89** | `Благоевград`, `Самоков`; el resto vacío o `.` |
| J | `Дата Постъпване` | Fecha de alta | 0 | No mencionada por el cliente |
| K | `Лична Карта` | Nº DNI búlgaro | 0 | **Dato sensible** |
| L | `Фирма` | Empresa del subcontratista | 2 | `Тимбърбилд`, `Гостун` |
| M–O | `Осигурен` / `от Фирма` / `от Служител` | Seguridad social: total / empresa / empleado | 1 | `480 / 300 / 180` — **dato salarial sensible** |
| P | `Размер Дрехи` | Talla de ropa | 3 | |
| Q | `Облекло` | Ropa entregada | 1 | `5 Тениски; 2 Елека; 1 Суитчер` |

Las columnas J–Q no están en el mensaje del cliente; hoy están casi vacías.

## 2. Lo que tenemos hoy

- `worker`: `name`, `location` (NOT NULL, default `''`), `lat`, `lng`, `role`
  (`CREW_MEMBER`/`CREW_LEADER`/`CREW_MANAGER`), `crew_id`, `phone`, `email`, `username`,
  `password_hash`, `password_plain`. Etapas en `worker_stage_type`.
- `crew`: `name` (hoy = **nombre completo del jefe**, p.ej. `Владимир Борисов`), `leader_id`,
  `manager_id`, `house_id`, `location`, `lat`, `lng`. Etapas en `crew_stage_type`.
- No hay `WorkerService` / `CrewService`: la lógica vive en `WorkerController` / `CrewController`
  (p.ej. cambiar el jefe llama a `houseStages.syncLeaderNameForCrew`).
- `lat`/`lng` de worker y crew se guardan y se muestran, pero **nada los usa para calcular**.
- El seed puso `location = 'София'` a casi todos: es relleno, la hoja no lo dice.

## 3. Cómo se cruzan

| Hoja | Nuestro campo | Estado |
|---|---|---|
| `Име Служител` | `worker.name` | Existe |
| `Роля` | `worker.role` + `crew.leader_id` | Existe (`Главен`→`CREW_LEADER`, `Служител`→`CREW_MEMBER`). `CREW_MANAGER` no viene en la hoja |
| `Група` | `crew_stage_type` / `worker_stage_type` | Existe, vía `stage_type.main_stage_bg` (tabla en §5) |
| `Бригада Име` | **no existe** | `crew.name` es el nombre completo; hace falta un campo apodo |
| `Телефон`, `Имейл` | `worker.phone`, `worker.email` | Existen; en el seed están todos a NULL |
| `Локация` | `crew.location` (+ `worker.location`) + `lat`/`lng` | Existe; habría que geocodificar el pueblo |
| `Вид служител` | **no existe** | Nueva columna (enum de 3) |
| `снимки` | **no existe** | Nueva columna URL (como `house.drive_folder_url`) |
| `Фирма` | **no existe** | Nueva columna texto |
| J, K, M–Q | **no existen** | Ver §7 — no los importaría sin decisión explícita |

## 4. Diferencias con el seed

- **15 personas nuevas**: brigadas nuevas `Мартин` (Конструкция), `Любо` (Покрив), `Киро` y `Ники`
  (ВиК), `Любо` (Изолация/Картон), más `Борислав Методиев` en `Виктор`, y las dos filas `1`/`2`.
- **3 que ya no están**: brigada `Огнян` de ВиК (crew 113: Огнян Пеев, Марио Петров, Пламен Пеев).
  Ojo: en el ACTIVE_MASTER `Огнян` sigue apareciendo como `Завършен` en 4 casas.
- **Brigadas**: 31 pares (grupo, apodo) en la hoja contra 26 crews en el seed.
- **Seed incoherente**: crew 103 (`Евгени`, Конструкция) tiene en el seed las etapas de Покрив
  (3,4,5,6,19). **En vivo ya está corregido** a la 2.

## 4b. Estado real de la BD (dev, `tedhouse_logistic`, vía API)

- **Igual que el seed**: 77 trabajadores (ids 411–487), 26 brigadas, ninguno añadido a mano.
  51 `CREW_MEMBER` + 26 `CREW_LEADER`, ningún `CREW_MANAGER`, ninguna brigada con manager.
- **Pertenencia a brigadas: 0 diferencias** con la hoja para las 74 personas que están en ambas
  (normalizando NFC y la E latina). Los roles también coinciden.
- **Teléfono: 0/77, email: 0/77, lat/lng: 0/77.** `location`: 74 × `София`, más `Самоков`,
  `Благоевград`, `София линк`. `crew.location` vacío en las 26. Todo lo de contacto sería nuevo.
- **Logins: los 77 tienen usuario y contraseña.** El importador no puede tocarlos, y las 15
  personas nuevas entrarían sin login (habría que crearlo a mano o generarlo).
- **Asistencias: 6 sesiones en total**, todas de `Владимир Борисов` (411). La brigada Огнян
  (455–457) no tiene ninguna, así que quitarla no pierde historia.
- **Etapas de casas (57 casas, 1539 celdas):** 651 llevan el apodo como texto (`worker_name`) y
  **solo 1 tiene `crew_id`** (casa #5, de prueba). Con la hoja, (etapa, apodo) resuelve **585 de
  651 (90 %) sin ninguna ambigüedad**. Las 66 restantes:

  | Etapa | Texto | Celdas | Por qué no resuelve |
  |---|---|---|---|
  | 24 Мълниезащита | `Кристиян` | 17 | Etapa sin brigada (`has_crew=false`); es el electricista |
  | 8 Ниво замазка | `Паскалев` | 13 | Etapa sin brigada; no está en la hoja |
  | 12 ВиК | `Митко`, `Коце`, `Огнян` | 18 | No están en la hoja |
  | 7/9 Дограма | `Етиком`, `Лазар Саламандър` | 13 | Empresa / nombre distinto de `Лазар` |
  | 26 Первази | `Вальо` | 2 | Etapa sin brigada; es el de Ламинат |
  | 22 Ламинат | `Михаил` | 1 | No está en la hoja |
  | 2, 14 | `Владимир Борисов`, `Иво Димитров` | 2 | Nombre completo: resuelve por el jefe |

- **Aparte, seguridad:** `GET /api/workers/credentials` devuelve `passwordPlain` de los 77
  trabajadores a cualquier admin. No forma parte de este import, pero si se añaden emails y
  teléfonos a esa misma tabla conviene revisarlo.

## 5. `Група` → etapas

Por `stage_type.main_stage_bg`, sin tabla nueva:

| Група | stage_order |
|---|---|
| Конструкция | 2 |
| Покрив | 3, 4, 5, 6, 19 |
| Дограма | 7, 9 |
| Ел | 11, 17, 23 |
| ВиК | 12 |
| Изолация/ Картон | 13, 18 + 14, 15, 21 (unión de `Изолация` y `Картон`) |
| Картон | 14, 15, 21 |
| Замазка | 16 |
| Плочки | 20 |
| Ламинат | 22 |

Фундамент (1) y las etapas sin brigada (8, 10, 24–27) no aparecen, coherente con `has_crew`.

## 6. Problemas de datos

1. **No hay columna de ID.** El importador empareja solo por clave externa (`import_ref`). Opciones:
   - (a) pedir al cliente una columna ID estable (`EMP-001`), como hicieron con `TH-…` — **recomendado**;
   - (b) clave = nombre: rompe con `Мартин Павлов` (dos personas distintas, Мартин/Конструкция y
     Киро/ВиК), con `1`/`2`, y con cualquier corrección de ortografía;
   - (c) clave = teléfono: falta en 11 filas y cambia.
2. **Unicode.** En la hoja la `й` está descompuesta (`и` + U+0306): `Николай Митов` y
   `Борчо Найденов` **no casan** con la BD sin normalizar a NFC.
3. **Homoglifos.** 8 filas de la brigada de Евгени dicen `Eвгени` con **E latina**: sin limpiar,
   salen dos brigadas (una sin jefe, otra con solo el jefe).
4. **Texto de relleno en celdas de datos**: `няма` (no hay), `чакам` (esperando), `.`,
   `Трябва да ги сложа` (tengo que ponerlos), `Иска да говори с Теодор[, няма да се чекира]`
   (quiere hablar con Teodor[, no va a fichar]). Lo último es una nota de negocio, no un email.
5. **Apodos repetidos entre grupos**: `Стоян`, `Любо`, `Краси`, `Ники`, `Жоро` existen en dos
   grupos cada uno. La clave de brigada tiene que ser (grupo, apodo).
6. **Tipo mezclado dentro de una brigada**: `Влади` = jefe `Подизпълнител` + miembros `Външен`;
   `Евгени` = jefe `Подизпълнител` + miembros `Вътрешен`. ¿El tipo es de la persona o de la brigada?
7. **`Локация`** es "de la brigada" según el cliente, pero solo va en la fila del jefe, y casi vacía.

## 7. Impacto de importarlo como CSV

**Esquema (un `V21`)**
- `worker`: `external_id`, `employment_type` (`INTERNAL`/`EXTERNAL`/`SUBCONTRACTOR`), `company`,
  `photo_url`. `location` debería admitir vacío de verdad (hoy el seed miente con `София`).
- `crew`: `code` o `nickname` (`Влади`) + grupo, único por (grupo, apodo).

**Importador** (patrón `HouseImporter`, `@Component`)
- Una fila trae **dos entidades**: la brigada se deduce de (`Група`, `Бригада Име`) y se crea/actualiza
  al vuelo; las etapas salen de §5; el jefe se fija con `Роля`.
- Antes hay que **sacar la lógica de `WorkerController`/`CrewController` a servicios** (alta, cambio
  de jefe + `syncLeaderNameForCrew`, etapas), si no el importador duplica reglas de los controllers.
- **No tocar nunca** `username`, `password_hash`, `password_plain`, `crew.house_id`, `manager_id`.
- **Nunca borrar**: quien desaparece de la hoja (Огнян) tiene asistencias y etapas colgando. Como
  mucho, avisar; la regla del importador ya es "never delete". Si se quiere "dar de baja", hace falta
  un `active` en `worker`.
- **Adopción / pre-seed obligatorio**: los ~74 trabajadores existentes deben mapearse a su clave
  antes del primer `apply`, o se duplican todos (mismo caso que las casas: `external_id` + adopción).
- Limpieza en el importador: NFC, homoglifos latinos en texto cirílico, placeholders → NULL con aviso,
  validación de email, normalización de teléfono, filas `1`/`2` → saltar con aviso.
- `import-openapi.yaml` + `ImportOpenApiSpecTest` en el mismo commit; i18n y modal de worker en el FE.

**Lo que desbloquea**
- **Vincular etapas de casas con brigadas.** El ACTIVE_MASTER nombra brigadas por el mismo apodo
  (`Влади Завършен`, `Слави Р в Процес`). Hoy `HouseStageImporter` deja `crew_id` sin tocar porque no
  sabe resolver el apodo; con (grupo de la columna, apodo) se resuelve sin ambigüedad. Apodos del
  ACTIVE_MASTER que **no** están en esta hoja: `Коце`, `Митко`, `Огнян` (ВиК), `Михаил` (Ламинат).
- **Desplazamientos**: con `Локация` geocodificada, la distancia brigada→casa sale del
  `RouteMatrixService` que ya existe. Hoy no hay datos (2/89).

## 8. Preguntas para el cliente

1. ¿Pueden añadir una columna ID fija por persona (como `TH-…` en las casas)?
2. `Вид служител` tiene 3 valores (`Вътрешен`, `Външен`, `Подизпълнител`); ¿es de la persona o de la brigada?
3. `снимки`: ¿la carpeta de Drive contiene la foto, o documentos? ¿Quieren la foto subida al sistema?
4. `Локация`: ¿por brigada (fila del jefe) o por persona? ¿Pueblo basta, o dirección?
5. Columnas J–Q (fecha de alta, DNI, seguridad social, ropa): ¿deben entrar en el sistema? DNI y
   salario son datos personales sensibles; si entran, solo admin.
6. En el ACTIVE_MASTER aparecen 5 nombres que no están en esta hoja. ¿Quiénes son y deben estar?
   - `Паскалев` — Ниво замазка (etapa 8), 13 casas
   - `Митко` — ВиК, 8 casas
   - `Коце` — ВиК, 6 casas
   - `Огнян` — ВиК, 4 casas; en el sistema es la brigada de Огнян Пеев (con Марио Петров y
     Пламен Пеев), que ya no viene en la hoja: ¿dejaron de trabajar con ustedes?
   - `Михаил` — Ламинат, 1 casa

   Y en Дограма: `Етиком` (10 casas) y `Лазар Саламандър` (3), ¿son la brigada `Лазар` de la
   hoja o una empresa aparte?
7. Filas `1`/`2` de `Борко` y las notas "quiere hablar con Teodor": ¿se importan o se esperan?

## 9. Respuestas del cliente (2026-10-12) y CSV nuevo

Archivo nuevo: `examples/Персонал_Мастър - Персонал.csv` — 91 filas, 18 columnas (nueva columna A `ID`).

### Respuestas

| # | Respuesta | Consecuencia |
|---|---|---|
| 1 ID | Añadida: `FR001`…, prefijo por grupo (`FR` Конструкция, `RO` Покрив, `WF` Дограма, `EL` Ел, `WS` ВиК, `IS` Изолация/Картон, `SC` Замазка, `TI` Плочки, `LF` Ламинат). 91 únicos, ninguno vacío | Clave externa = `ID`. Se puede hacer `worker.external_id` + adopción como en casas |
| 2 Tipo | Es **de la persona**: `Вътрешен` = brigadas propias de TH y sus miembros; `Подизпълнител` = empresa que trabaja para TH (Влади, Евгени); `Външен` = miembros de esa empresa | `worker.employment_type` (enum 3) |
| 3 Fotos | Sin respuesta | Ahora hay 65 enlaces a **ficheros** de Drive (`/file/d/…`), 2 a carpetas, más `няма` ×3 y `Трябва да ги сложа`. Guardar como URL, no descargar |
| 4 Локация | La están recogiendo, quieren precisión para los desplazamientos | Hoy 2 valores reales; 27 jefes tienen `.` (placeholder → NULL) |
| 5 J–Q | Se borrarán M, N, O (`Осигурен`, `от Фирма`, `от Служител`). El resto (fecha de alta, `Лична Карта`, `Фирма`, talla, ropa) **se guarda** con la regla de datos sensibles | `Лична Карта` solo admin, nunca en logs/listados/export. **Las 3 columnas siguen en el CSV** (FR001: 480/300/180): el importador debe ignorarlas aunque vengan |
| 6 Nombres | `Паскалев` se añadirá; `Митко`, `Коце`, `Огнян` (ВиК) ya no trabajan con TH; `Михаил` (Ламинат) es un usuario de prueba, "pueden borrarlo de producción" | Sin `active` en `worker`, la brigada Огнян (113) se queda. Borrar `Михаил` en prod **pendiente de decisión nuestra** (no hecho) |
| 6b Етиком / Лазар Саламандър | Sin respuesta | Siguen sin resolver 13 celdas de Дограма |
| 7 Борко `1`/`2` | Sin respuesta | Siguen en el CSV (RO017/RO018) |

### Diferencias del CSV nuevo contra la versión del 08-10

- **Corrección de nombre**: `Слави Василев` → `Слави Веселинов` (IS008, mismo teléfono/email). Con
  el ID esto es un update; con clave por nombre habría sido un duplicado.
- **Nuevo**: `Васил Димитров` (IS006, brigada Слави Р).
- **Brigada `Любо` (Изолация/Картон)**: desaparecen `Даниел Панов` y `Методии Веселинов`, entran 3 filas
  **sin nombre** (IS019–IS021), y IS019 está marcada `Главен` → la brigada tiene 2 jefes. El jefe
  `Любомир Фарашев` pasa de `Вътрешен` a `Подизпълнител`.
- Teléfonos nuevos para FR013, FR014; ~45 fotos nuevas.
- `Паскалев` **aún no está** en el CSV.

### Problemas que siguen

1. `Eвгени` con **E latina** en FR016–FR023; el jefe FR015 está con `Е` cirílica → sin limpiar, la
   brigada sale sin jefe.
2. `й` descompuesta en FR023 (`Николай Митов`) y RO010 (`Борчо Найденов`) → NFC.
3. `Плочки ` con espacio final (TI002).
4. Placeholders: `няма` (18 emails, 2 teléfonos), `чакам`, `Иска да говори с Теодор[, няма да се чекира]`
   (5 emails), `.` en Локация.
5. Filas sin nombre: RO017/RO018 (`1`/`2`) e IS019–IS021 (vacío) → saltar con aviso.
6. **Contradicción con la respuesta 2**: brigada `Евгени` = jefe `Подизпълнител` + 7 miembros
   `Вътрешен`; según la regla deberían ser `Външен`. (`Влади` y `Любо` sí cumplen.)

### Preguntas que quedan para el cliente

1. Los miembros de la brigada de Евгени (FR016–FR023), ¿son `Външен`? Hoy dicen `Вътрешен`.
2. Brigada `Любо` (Изолация/Картон): ¿quiénes son las 3 filas vacías (IS019–IS021) y quién es el jefe?
   ¿Даниел Панов y Методии Веселинов ya no están?
3. `Борко` `1`/`2` (RO017/RO018): ¿se importan sin nombre o se esperan?
4. `Етиком` (10 casas) y `Лазар Саламандър` (3) en Дограма: ¿son la brigada `Лазар` (WF001)?
5. `снимки`: ¿es la foto de la persona? (La guardaríamos como enlace, sin copiarla.)
6. ¿Cuándo llega `Паскалев` y en qué grupo? (`Ниво замазка` no tiene brigada en el sistema: `has_crew=false`.)

## 10. Importador `workers` — implementado (2026-10-12, rama `staff-import`, sin commit)

V21 (`worker.external_id`, `employment_type`, `company`, `photo_url`, `hire_date`, `id_card_number`,
`clothing_size`, `clothing_issued`; `crew.stage_group` + `crew.nickname`), `WorkerImporter`,
`CrewMembershipService`, campos en el modal del trabajador (DNI solo admin), contrato OpenAPI.

**Ensayo en seco** (seed V1 = dev, CSV del 12-10, en memoria, sin servidor):

| Pasada | created | updated | unchanged | failed | Avisos |
|---|---|---|---|---|---|
| validate | 13 | 73 | 0 | 5 | 73 ADOPTED_EXISTING, 26 CELL_IGNORED, 4 NOT_IN_FILE, 3 UNKNOWN_COLUMN (M–O) |
| apply | 13 | 73 | 0 | 5 | igual |
| apply otra vez | 0 | 0 | **86** | 5 | se estabiliza |

- Los 5 fallos son las filas sin nombre: RO017/RO018 (`1`, `2`) e IS019–IS021.
- `Eвгени` (E latina) acaba en una sola brigada de 9 con su jefe; las ubicaciones de Евгени
  (Благоевград) y Благо (Самоков) pasan a la brigada.
- **Paso manual antes del primer `apply`**: renombrar en la app `Слави Василев` → `Слави Веселинов`
  (id 466 en dev). Sin eso el cambio de nombre no se reconoce y se crean una persona y una brigada
  `Слави В` duplicadas. Con el renombrado: 74 adoptados, 12 nuevos, 31 brigadas, y solo la brigada
  de Огнян (455–457) como NOT_IN_FILE.
- La ubicación de relleno `София` del seed se vacía donde la hoja trae `.` (la hoja manda).

**Runbook por entorno** (dev, luego prod): desplegar (aplica V21) → renombrar a Слави → `POST
/api/import/workers?mode=validate` y comprobar 74 `ADOPTED_EXISTING` / 12 `created` → `mode=apply`
→ repetir `apply` y ver todo `unchanged`.

**Renombrar también la brigada.** Renombrar al jefe en la app no renombra su brigada, y el
importador solo la renombra cuando es él quien cambia el nombre del jefe. Así que, además del
trabajador, renombrar la brigada `Слави Василев` → `Слави Веселинов` (crew 116 en dev), antes o
después del `apply`.

### Ejecutado en dev (2026-10-13)

| Paso | Resultado |
|---|---|
| Despliegue | V21 aplicada; `workers` aparece en `/api/import/entities` |
| Guardado de campos nuevos (worker 452) | los 7 se guardan y se leen (cirílico incluido); restaurado al original |
| Renombrar 466 | ok; brigada 116 renombrada a mano después (ver arriba) |
| `validate` | 91 leídas, 12 creados, 74 actualizados (74 `ADOPTED_EXISTING`, ids distintos), 5 fallidos, 26 `CELL_IGNORED`, 3 `UNKNOWN_COLUMN`, 3 `NOT_IN_FILE` (Огнян 455–457) |
| `apply` | idéntico al `validate`. 26 → 31 brigadas (30 con grupo + apodo; la de Огнян no tiene), 77 → 89 trabajadores (86 con External ID); rol y brigada de cada fila coinciden con la hoja |
| segundo `apply` | 86 `unchanged`, 0 creados, 0 actualizados |

Tipos tras el import: 69 `INTERNAL`, 10 `EXTERNAL`, 7 `SUBCONTRACTOR` (+3 de Огнян sin tipo).
