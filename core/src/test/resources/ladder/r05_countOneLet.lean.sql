WITH frame_r AS (SELECT t0.ID AS id, t0.NAME AS name, t0.AMOUNT AS amount FROM T AS t0),
__a AS (SELECT COUNT(*) AS n FROM frame_r)
SELECT 0 AS __ix, (a.n IS NOT DISTINCT FROM 3) AS __verdict, '3' AS __expected, CAST(a.n AS VARCHAR) AS __actual, CAST(NULL AS VARCHAR) AS __unjudged, FALSE AS __lenient
FROM __a AS a
