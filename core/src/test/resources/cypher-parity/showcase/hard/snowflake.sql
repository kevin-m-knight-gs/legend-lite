SELECT "t0"."p_legalname" AS "firm", "t1"."p_name" AS "city", NOT CASE WHEN NOT EXISTS (SELECT * FROM "n_city" AS "t6" WHERE "t0"."r_hq" = "t6"."id") THEN NULL ELSE PARSE_JSON(('{"@labels":' || '["City"]' || COALESCE(CASE WHEN "t1"."p_name" IS NULL THEN '' ELSE (',"name":' || '"' || COALESCE(replace(replace("t1"."p_name", '\\', '\\\\'), '"', '\\"'), '') || '"') END, '') || '}')) END IS NOT NULL AS "noHq", CASE WHEN "t3"."agg_0" IS NOT NULL THEN "t3"."agg_0" ELSE 0 END AS "headcount", PARSE_JSON(('[' || COALESCE(substr("t5"."agg_0", 2), '') || ']')) AS "seniors", CASE WHEN "t0"."p_founded" IS NOT NULL AND "t0"."p_founded" IS NOT NULL AND "t0"."p_founded" IS NOT NULL AND "t0"."p_founded" < 2000 THEN TRUE ELSE CASE WHEN NOT "t0"."p_founded" IS NOT NULL OR "t0"."p_founded" IS NOT NULL AND NOT ("t0"."p_founded" IS NOT NULL AND "t0"."p_founded" < 2000) THEN FALSE ELSE NULL END END AS "old", CASE WHEN NOT CASE WHEN NOT EXISTS (SELECT * FROM "n_city" AS "t7" WHERE "t0"."r_hq" = "t7"."id") THEN NULL ELSE PARSE_JSON(('{"@labels":' || '["City"]' || COALESCE(CASE WHEN "t1"."p_name" IS NULL THEN '' ELSE (',"name":' || '"' || COALESCE(replace(replace("t1"."p_name", '\\', '\\\\'), '"', '\\"'), '') || '"') END, '') || '}')) END IS NOT NULL THEN 'no hq' ELSE CASE WHEN "t1"."p_name" IS NULL THEN NULL ELSE ('in ' || COALESCE("t1"."p_name", '')) END END AS "where"
FROM "n_firm" AS "t0"
LEFT OUTER JOIN "n_city" AS "t1" ON "t0"."r_hq" = "t1"."id"
LEFT OUTER JOIN (
  SELECT "t2"."r_works_at" AS "r_works_at", COUNT(*) AS "agg_0"
  FROM "n_person" AS "t2"
  GROUP BY "t2"."r_works_at"
) AS "t3" ON "t3"."r_works_at" = "t0"."id"
LEFT OUTER JOIN (
  SELECT "t4"."r_works_at" AS "r_works_at", (CASE WHEN COUNT((',' || COALESCE(CASE WHEN "t4"."p_name" IS NULL THEN 'null' ELSE ('"' || COALESCE(replace(replace("t4"."p_name", '\\', '\\\\'), '"', '\\"'), '') || '"') END, ''))) = 0 THEN NULL ELSE LISTAGG((',' || COALESCE(CASE WHEN "t4"."p_name" IS NULL THEN 'null' ELSE ('"' || COALESCE(replace(replace("t4"."p_name", '\\', '\\\\'), '"', '\\"'), '') || '"') END, '')), '') END) AS "agg_0"
  FROM "n_person" AS "t4"
  WHERE "t4"."p_age" IS NOT NULL AND "t4"."p_age" IS NOT NULL AND "t4"."p_age" > 35
  GROUP BY "t4"."r_works_at"
) AS "t5" ON "t5"."r_works_at" = "t0"."id"
ORDER BY "t0"."p_legalname" NULLS LAST
