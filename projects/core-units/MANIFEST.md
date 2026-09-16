# core-units

Layer 0, no dependencies. Units of measure for physical commodities -- barrels, therms, MWh,
troy ounces, metric tonnes, bushels -- with their conversion factors and their precision.

Every table is declared inside the `uom` SCHEMA, so a downstream project that includes this
store must reference the tables schema-qualified:

    ~mainTable [core_units::Store]uom.CU_UNIT
    unitCode: [core_units::Store]uom.CU_UNIT.UNIT_CODE

## Exports

| element | kind | note |
| --- | --- | --- |
| core_units::CuQuantityKind | class | what is measured: volume, energy, mass, count; names the base unit of its kind |
| core_units::CuUnit | class | one unit, with `factorToBase`, `decimals` and `quantityKind`; qualified `isBaseUnit()`, `toBase(Float)`, `label()` |
| core_units::CuConversion | class | published conversion for an ordered unit pair; qualified `convert(Float)` |
| core_units::CuUnitPrecision | class | rounding of a unit in a context (exchange, settlement, invoice) |
| core_units::Store | store | schema `uom`; tables CU_QUANTITY_KIND, CU_UNIT, CU_CONVERSION, CU_UNIT_PRECISION; joins Cu_UnitKind, Cu_ConversionFrom, Cu_ConversionTo, Cu_PrecisionUnit |
| core_units::Mapping | mapping | set ids cuQuantityKind, cuUnit, cuConversion, cuUnitPrecision |

## Tables

| table | primary key | note |
| --- | --- | --- |
| uom.CU_QUANTITY_KIND | KIND_CODE CHAR(4) | KIND_NAME VARCHAR(40), BASE_UNIT_CODE CHAR(6), IS_RATIO_SCALE BIT |
| uom.CU_UNIT | UNIT_CODE CHAR(6) | DECIMALS SMALLINT, FACTOR_TO_BASE NUMERIC(20,8), SYMBOL VARCHAR(8), IS_SI_UNIT BIT |
| uom.CU_CONVERSION | FROM_UNIT, TO_UNIT CHAR(6) | composite; FACTOR and OFFSET_VALUE NUMERIC(20,8), REVISION SMALLINT, EFFECTIVE_FROM DATE, TOLERANCE_PCT DOUBLE |
| uom.CU_UNIT_PRECISION | UNIT_CODE, CONTEXT_CODE CHAR(8) | composite; DECIMALS SMALLINT, MIN_INCREMENT NUMERIC(20,8), LOT_SIZE INTEGER |

## Joins

| join | condition |
| --- | --- |
| Cu_UnitKind | uom.CU_UNIT.KIND_CODE = uom.CU_QUANTITY_KIND.KIND_CODE |
| Cu_ConversionFrom | uom.CU_CONVERSION.FROM_UNIT = uom.CU_UNIT.UNIT_CODE |
| Cu_ConversionTo | uom.CU_CONVERSION.TO_UNIT = uom.CU_UNIT.UNIT_CODE |
| Cu_PrecisionUnit | uom.CU_UNIT_PRECISION.UNIT_CODE = uom.CU_UNIT.UNIT_CODE |

No `###Data` element, no Runtime; the tables are declared and unseeded. `REAL` is not used
anywhere -- DOUBLE and NUMERIC carry the non-integer columns instead.
