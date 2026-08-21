# core-types

Layer 0, no dependencies. Exports enums, a governance profile and standalone functions.
No `Store`, no `Mapping` — downstream projects map these enums onto their own columns with
their own `enumerationMapping`.

Prefixes: elements `Ct`, tables `CT_` (none declared), set ids `ct`.

| element | kind | note |
| --- | --- | --- |
| core_types::CtGovernance | profile | stereotypes `reviewed`, `deprecated`, `restricted`; tags `owner`, `since`, `ticket` |
| core_types::CtCurrency | enum | ISO 4217 alpha codes, 25 values, spanning 0/2/3 minor units |
| core_types::CtCountry | enum | ISO 3166-1 alpha-2, 26 values, covers every CtCurrency issuer |
| core_types::CtUnitOfMeasure | enum | 13 commodity units (BARREL, TROY_OUNCE, MMBTU, MEGAWATT_HOUR, …) |
| core_types::CtRoundingMode | enum | 5 values: HALF_UP, HALF_EVEN, CEILING, FLOOR, DOWN |
| core_types::CtMoney | class | `amount: Float[1]`, `currency: CtCurrency[1]`; derived `minorUnits: Integer[1]`, `roundedAmount: Float[1]`. Stereotyped `<<CtGovernance.reviewed>>` |
| core_types::CtQuantity | class | `magnitude: Float[1]`, `unit: CtUnitOfMeasure[1]`. Stereotyped `<<CtGovernance.reviewed>>` |
| core_types::CtJurisdiction | class | `country: CtCountry[1]`, `settlementCurrency: CtCurrency[1]`, `taxRegimeCode: String[0..1]` (property stereotyped `restricted`) |
| core_types::CtAuditStamp | class | `reviewedBy: String[1]`, `reviewedOn: StrictDate[1]`, `ticketRef: String[0..1]`, `supersedes: String[0..1]` |
| core_types::CtLegacyLot | class | `lots: Integer[1]`, `lotSize: Float[1]`. Stereotyped `<<CtGovernance.deprecated>>` — do not build on it |
| core_types::ctMinorUnits | function | `(CtCurrency[1]): Integer[1]` — ISO 4217 minor-unit digits; 0 for JPY/KRW/CLP/ISK/VND, 3 for KWD/BHD/JOD/OMR/TND, else 2 |
| core_types::ctRoundToMinorUnits | function | `(Float[1], CtCurrency[1]): Float[1]` — round an amount to its own currency's scale |
| core_types::ctBasisPointsToRate | function | `(Float[1]): Float[1]` — bp / 10000.0 |
| core_types::ctValueDate | function | `(StrictDate[1], Integer[1]): Date[1]` — T+n as a **calendar**-day shift; no holiday adjustment (that needs core-calendar) |

## Notes for downstream projects

- Call the functions extension-style: `$m.amount->core_types::ctRoundToMinorUnits($m.currency)`,
  or by path: `core_types::ctBasisPointsToRate(25.0)`.
- `ctValueDate` returns `Date[1]`, not `StrictDate[1]` — that is `adjust`'s return type. Narrow
  it downstream if you need a `StrictDate`.
- Apply the profile to your own elements as `<<core_types::CtGovernance.reviewed>>` and
  `{core_types::CtGovernance.owner = '…'}`; there is no import, so the full path is required.
- `CtRoundingMode` is a declaration of intent only — nothing in this project reads it. The
  rounding function is half-up by way of `round`.
