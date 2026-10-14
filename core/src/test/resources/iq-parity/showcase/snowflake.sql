SELECT "t0"."id" AS "Row Id", (COALESCE(coalesce(CASE WHEN "t0"."vc" IS NULL THEN NULL ELSE left("t0"."vc", CASE WHEN FALSE THEN 0 ELSE 3 END) END, ''), '') || ':' || COALESCE("t6"."labels", '')) AS "Tag", "t6"."n" AS "Labels", "t6"."last_u" AS "Last u", CASE WHEN "t0"."vc" IS NOT NULL AND (startswith("t0"."vc", 'x') OR startswith('x', "t0"."vc")) AND replace("t0"."vc", ' ', '') = replace('x', ' ', '') THEN 'Y' ELSE CASE WHEN "t0"."vc" IS NOT NULL AND NOT ((startswith("t0"."vc", 'x') OR startswith('x', "t0"."vc")) AND replace("t0"."vc", ' ', '') = replace('x', ' ', '')) THEN 'N' ELSE NULL END END AS "Is x", coalesce(CASE WHEN "t0"."vc" IS NULL THEN NULL ELSE CHARINDEX('l', "t0"."vc") END, -1) AS "First l", CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END IS NULL THEN NULL ELSE CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END = 1 THEN 'Sunday' ELSE CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END = 2 THEN 'Monday' ELSE CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END = 3 THEN 'Tuesday' ELSE CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END = 4 THEN 'Wednesday' ELSE CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END = 5 THEN 'Thursday' ELSE CASE WHEN CASE WHEN "t0"."dt" IS NULL THEN NULL ELSE MOD(MOD(DAYOFWEEKISO("t0"."dt"), 7) + 7, 7) + 1 END = 6 THEN 'Friday' ELSE 'Saturday' END END END END END END END AS "Day"
FROM "t" AS "t0"
JOIN (
  SELECT "t5"."id" AS "tid", "t5"."n" AS "n", "t5"."last_u" AS "last_u", CASE WHEN "t5"."_sql_1" IS NULL THEN '' ELSE "t5"."_sql_1" END AS "labels"
  FROM (
    SELECT "t1"."id" AS "id", COUNT("t4"."v_id") AS "n", MAX("t4"."v_id") AS "last_u", (CASE WHEN COUNT(CASE WHEN "t4"."lbl" IS NULL THEN NULL ELSE CASE WHEN "t4"."lbl" = '' THEN NULL ELSE "t4"."lbl" END END) = 0 THEN NULL ELSE LISTAGG(CASE WHEN "t4"."lbl" IS NULL THEN NULL ELSE CASE WHEN "t4"."lbl" = '' THEN NULL ELSE "t4"."lbl" END END, ',') WITHIN GROUP (ORDER BY "t4"."lbl" ASC) END) AS "_sql_1"
    FROM "t" AS "t1"
    LEFT OUTER JOIN (
      SELECT "t3"."id" AS "v_id", "t3"."t_id" AS "t_id", "t3"."lbl" AS "lbl"
      FROM (
        SELECT "t2"."id" AS "id", "t2"."t_id" AS "t_id", CASE WHEN "t2"."label" IS NULL THEN NULL ELSE REGEXP_REPLACE("t2"."label", '(\\n|[\\t-\\r ])+$', '', 1, 1, 'c') END AS "lbl"
        FROM "u" AS "t2"
      ) AS "t3"
    ) AS "t4" ON "t4"."t_id" IS NOT NULL AND "t1"."id" IS NOT NULL AND "t4"."t_id" = "t1"."id"
    GROUP BY "t1"."id"
  ) AS "t5"
) AS "t6" ON "t6"."tid" IS NOT NULL AND "t0"."id" IS NOT NULL AND "t6"."tid" = "t0"."id"
WHERE "t0"."vc" IS NOT NULL AND NOT ((startswith("t0"."vc", 'hello') OR startswith('hello', "t0"."vc")) AND replace("t0"."vc", ' ', '') = replace('hello', ' ', '')) OR "t0"."vc" IS NULL
ORDER BY "t0"."id" DESC NULLS LAST
LIMIT 5
OFFSET 1
