SELECT t0.ID AS id, t0.NAME AS name
FROM T AS t0
ORDER BY t0.ID NULLS LAST
;;
SELECT t0.ID AS id, t0.NAME AS name
FROM T AS t0
ORDER BY t0.ID NULLS LAST
;;
WITH frame_r AS MATERIALIZED (SELECT frame_r__t0.ID AS id, frame_r__t0.NAME AS name
  FROM T AS frame_r__t0
  ORDER BY frame_r__t0.ID NULLS LAST), __a_0 AS (SELECT CAST(w.__rowcanon AS VARCHAR) AS __c, ROW_NUMBER() OVER () AS __rn, CAST(NULL AS DOUBLE) AS __v
  FROM (
    SELECT id AS id, name AS name, coalesce(CAST(CAST(id AS VARCHAR) AS VARCHAR), 'TDSNull') AS __cell0, CASE WHEN name IS NULL THEN 'TDSNull' WHEN strpos(name, '') > 0 THEN NULL ELSE coalesce(CAST(concat('''', replace(replace(name, '\', '\\'), '''', '\'''), '''') AS VARCHAR), 'TDSNull') END AS __cell1, concat(coalesce(CAST(CAST(id AS VARCHAR) AS VARCHAR), 'TDSNull'), '', CASE WHEN name IS NULL THEN 'TDSNull' WHEN strpos(name, '') > 0 THEN NULL ELSE coalesce(CAST(concat('''', replace(replace(name, '\', '\\'), '''', '\'''), '''') AS VARCHAR), 'TDSNull') END) AS __rowcanon
    FROM (
      SELECT frame_r_t0.id AS id, frame_r_t0.name AS name
      FROM frame_r AS frame_r_t0
    ) AS side
  ) AS w), __n_0 AS (SELECT CAST(value AS VARCHAR) AS __c, ROW_NUMBER() OVER () AS __rn
  FROM (
    SELECT 3 AS value
  ) AS side
  WHERE value IS NOT NULL)
SELECT 0 AS __ix, coalesce(((SELECT COUNT(__a.__rn) AS __n FROM __a_0 AS __a) IS NOT DISTINCT FROM CAST((SELECT __n.__c AS __one FROM __n_0 AS __n ORDER BY __n.__rn NULLS LAST LIMIT 1) AS BIGINT)), FALSE) AS __verdict, CAST(CAST((SELECT __n.__c AS __one FROM __n_0 AS __n ORDER BY __n.__rn NULLS LAST LIMIT 1) AS BIGINT) AS VARCHAR) AS __expected, CAST((SELECT COUNT(__a.__rn) AS __n FROM __a_0 AS __a) AS VARCHAR) AS __actual, CAST(NULL AS VARCHAR) AS __unjudged, FALSE AS __lenient
