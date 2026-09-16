# core-ratings

Layer 0, no dependencies. Package root `core_ratings::`, prefix `CR` / `Cr` / `cr`.

Credit ratings held as history rather than as a current value. The one thing to know before
depending on this project: **`core_ratings::RatingVersion` is `<<temporal.businesstemporal>>`**,
so `all()` on it takes a date -- `core_ratings::RatingVersion.all(%2024-03-31)`,
`core_ratings::RatingVersion.all(%latest)` -- and any navigation that reaches it from your
project must carry a date too.

| element | kind | note |
| --- | --- | --- |
| core_ratings::CrAgency | class | rating agency: agencyId, agencyName, scaleFamily |
| core_ratings::CrRatingScale | class | one notch of one agency's scale: symbol, notch, isInvestmentGrade, agency |
| core_ratings::RatingVersion | class | **business-temporal**: entityId, rating, agency. No from/thru properties -- `all(<date>)` required |
| core_ratings::CrRatingAction | class | published action: actionId, entityId, actionType, priorRating, newRating, actionAgency |
| core_ratings::Store | store | tables CR_AGENCY, CR_SCALE, CR_RATING_MS, CR_ACTION; joins Cr_ScaleAgency, Cr_ActionAgency |
| core_ratings::Mapping | mapping | set ids crAgency, crRatingScale, crRatingVersion, crRatingAction |

## The milestoned table

    Table CR_RATING_MS
    (
      milestoning ( business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, INFINITY_DATE = %9999-12-31) )
      ENTITY_ID VARCHAR(20) PRIMARY KEY,
      FROM_Z DATE PRIMARY KEY,
      THRU_Z DATE,
      RATING VARCHAR(8),
      AGENCY VARCHAR(20)
    )

`INFINITY_DATE` is present, so `%latest` works; without it only `%latest` fails, and it fails
late, at plan generation. The primary key is composite (ENTITY_ID + FROM_Z) because an entity
has many versions.

The mapping's `~primaryKey` names `ENTITY_ID` and `FROM_Z`. `FROM_Z` / `THRU_Z` appear in the
store and the mapping key and nowhere on the class -- the engine supplies the date predicate.

## Values

`RATING` holds real symbols, unnormalised, as the agency publishes them: `AAA`, `AA+`, `AA`,
`AA-`, `A+`, `A`, `A-`, `BBB+`, `BBB`, `BBB-`, `BB+`, ... `D` from S&P Global Ratings, Fitch
Ratings and DBRS Morningstar; `Aaa`, `Aa1`, `Aa2`, `Aa3`, `A1`, `A2`, `A3`, `Baa1`, `Baa2`,
`Baa3`, `Ba1`, ... `C` from Moody's Investors Service. `CR_SCALE` is what makes the two
comparable: equal `NOTCH` means equal grade, and `IS_INVESTMENT_GRADE` turns at `BBB-` /
`Baa3`.

## Not here

No `###Data` element and no Runtime -- tables are declared and unseeded.
