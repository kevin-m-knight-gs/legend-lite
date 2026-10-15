SELECT t0.id AS "Row Id", concat(coalesce(CASE WHEN t0.vc IS NULL THEN NULL ELSE left(t0.vc, CASE WHEN FALSE THEN 0 ELSE 3 END) END, ''), ':', t6.labels) AS Tag, t6.n AS Labels, t6.last_u AS "Last u", CASE WHEN t0.vc IS NOT NULL AND (starts_with(t0.vc, 'x') OR starts_with('x', t0.vc)) AND replace(t0.vc, ' ', '') = replace('x', ' ', '') THEN 'Y' ELSE CASE WHEN t0.vc IS NOT NULL AND NOT ((starts_with(t0.vc, 'x') OR starts_with('x', t0.vc)) AND replace(t0.vc, ' ', '') = replace('x', ' ', '')) THEN 'N' ELSE NULL END END AS "Is x", coalesce(CASE WHEN t0.vc IS NULL THEN NULL ELSE strpos(t0.vc, 'l') END, -1) AS "First l", CASE WHEN t0.dt IS NULL THEN NULL ELSE CASE WHEN MOD(MOD(date_part('isodow', t0.dt), 7) + 7, 7) + 1 = 1 THEN 'Sunday' ELSE CASE WHEN MOD(MOD(date_part('isodow', t0.dt), 7) + 7, 7) + 1 = 2 THEN 'Monday' ELSE CASE WHEN MOD(MOD(date_part('isodow', t0.dt), 7) + 7, 7) + 1 = 3 THEN 'Tuesday' ELSE CASE WHEN MOD(MOD(date_part('isodow', t0.dt), 7) + 7, 7) + 1 = 4 THEN 'Wednesday' ELSE CASE WHEN MOD(MOD(date_part('isodow', t0.dt), 7) + 7, 7) + 1 = 5 THEN 'Thursday' ELSE CASE WHEN MOD(MOD(date_part('isodow', t0.dt), 7) + 7, 7) + 1 = 6 THEN 'Friday' ELSE 'Saturday' END END END END END END END AS Day
FROM t AS t0
JOIN (
  SELECT t5.id AS tid, t5.n, t5.last_u, CASE WHEN t5._sql_1 IS NULL THEN '' ELSE t5._sql_1 END AS labels
  FROM (
    SELECT t1.id, COUNT(t4.v_id) AS n, MAX(t4.v_id) AS last_u, STRING_AGG(CASE WHEN t4.lbl IS NULL THEN NULL ELSE CASE WHEN t4.lbl = '' THEN NULL ELSE t4.lbl END END, ',' ORDER BY t4.lbl ASC) AS _sql_1
    FROM t AS t1
    LEFT OUTER JOIN (
      SELECT t3.id AS v_id, t3.t_id, t3.lbl
      FROM (
        SELECT t2.id, t2.t_id, CASE WHEN t2.label IS NULL THEN NULL ELSE regexp_replace(t2.label, '(\n|[\t-\r ])+$', '', '') END AS lbl
        FROM u AS t2
      ) AS t3
    ) AS t4 ON t4.t_id IS NOT NULL AND t1.id IS NOT NULL AND t4.t_id = t1.id
    GROUP BY t1.id
  ) AS t5
) AS t6 ON t6.tid IS NOT NULL AND t0.id IS NOT NULL AND t6.tid = t0.id
WHERE t0.vc IS NOT NULL AND NOT ((starts_with(t0.vc, 'hello') OR starts_with('hello', t0.vc)) AND replace(t0.vc, ' ', '') = replace('hello', ' ', '')) OR t0.vc IS NULL
ORDER BY t0.id DESC NULLS LAST
LIMIT 5
OFFSET 1
