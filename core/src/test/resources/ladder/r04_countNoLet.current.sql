WITH __n_0 AS (SELECT CAST(value AS VARCHAR) AS __c, ROW_NUMBER() OVER () AS __rn
  FROM (
    SELECT 3 AS value
  ) AS side
  WHERE value IS NOT NULL)
SELECT 0 AS __ix, coalesce(((SELECT COUNT(*) AS __n FROM T AS t0) IS NOT DISTINCT FROM CAST((SELECT __n.__c AS __one FROM __n_0 AS __n ORDER BY __n.__rn NULLS LAST LIMIT 1) AS BIGINT)), FALSE) AS __verdict, CAST(CAST((SELECT __n.__c AS __one FROM __n_0 AS __n ORDER BY __n.__rn NULLS LAST LIMIT 1) AS BIGINT) AS VARCHAR) AS __expected, CAST((SELECT COUNT(*) AS __n FROM T AS t0) AS VARCHAR) AS __actual, CAST(NULL AS VARCHAR) AS __unjudged, FALSE AS __lenient
