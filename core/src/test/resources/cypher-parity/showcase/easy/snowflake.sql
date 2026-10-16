SELECT "t0"."p_name" AS "name", "t0"."p_age" AS "age", CASE WHEN CASE WHEN "t0"."p_active" IS NOT NULL AND "t0"."p_active" THEN TRUE ELSE CASE WHEN "t0"."p_active" IS NOT NULL AND NOT "t0"."p_active" THEN FALSE ELSE NULL END END IS NULL THEN FALSE ELSE CASE WHEN "t0"."p_active" IS NOT NULL AND "t0"."p_active" THEN TRUE ELSE CASE WHEN "t0"."p_active" IS NOT NULL AND NOT "t0"."p_active" THEN FALSE ELSE NULL END END END AS "active"
FROM "n_person" AS "t0"
WHERE "t0"."p_age" IS NOT NULL AND "t0"."p_age" IS NOT NULL AND "t0"."p_age" > 28
ORDER BY "t0"."p_age" DESC NULLS FIRST
