"""
Which cross-PROJECT dependency forms actually compile?

A Legend project depends on another by referring to its packageable elements: extending its
classes, including its stores and mappings, associating with its classes, using its enums and
calling its functions. In this corpus everything has lived in one flat namespace, so none of
those references has ever crossed a boundary that could be broken.

This probe declares an UPSTREAM project and a DOWNSTREAM one that depends on it, one
dependency form at a time, and reports which compile. It is the contract every generated
project has to satisfy, so it is verified before any are generated rather than after.

Each form is its own model: a form that fails to compile takes the whole file with it, and
the point is to know WHICH form failed.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as runner

# The upstream project: a class, an enum, a store, a mapping, a function, a profile and an
# association end to extend, include, reference and call.
UPSTREAM = """###Pure
Enum up::Grade
{
   HIGH,
   LOW
}

Profile up::Governed
{
   stereotypes: [ reviewed, deprecated ];
   tags: [ owner, since ];
}

Class up::BaseRecord
{
   recordId: String[1];
   label: String[0..1];
}

function up::bump(v: Float[1]): Float[1]
{
   $v * 1.1
}

###Relational
Database up::DB
(
   Table UP_RECORD ( RECORD_ID VARCHAR(20) PRIMARY KEY, LABEL VARCHAR(40), GRADE VARCHAR(8), AMOUNT DOUBLE )
)

###Mapping
Mapping up::UpMapping
(
   up::Grade: EnumerationMapping GradeMapping
   {
      HIGH: ['HIGH'],
      LOW: ['LOW']
   }

   up::BaseRecord: Relational
   {
      ~primaryKey ( [up::DB]UP_RECORD.RECORD_ID )
      ~mainTable [up::DB]UP_RECORD
      recordId: [up::DB]UP_RECORD.RECORD_ID,
      label: [up::DB]UP_RECORD.LABEL
   }
)
"""

# Each downstream form. (name, extra ###Pure, extra ###Relational, extra ###Mapping)
FORMS = [
    ("ExtendsClass",
     "Class down::Child extends up::BaseRecord\n{\n   extra: String[0..1];\n}",
     "", ""),
    ("UsesEnum",
     "Class down::Graded\n{\n   k: String[1];\n   grade: up::Grade[1];\n}",
     "", ""),
    ("AssociationAcross",
     "Class down::Sibling\n{\n   k: String[1];\n}\n\n"
     "Association down::Link\n{\n   base: up::BaseRecord[0..1];\n   siblings: down::Sibling[*];\n}",
     "", ""),
    ("CallsFunction",
     "Class down::Computed\n{\n   k: String[1];\n   amount: Float[1];\n\n"
     "   bumped() { up::bump($this.amount) } : Float[1];\n}",
     "", ""),
    ("AppliesProfile",
     "Class <<up::Governed.reviewed>> "
     "{up::Governed.owner = 'risk-it'} down::Tagged\n{\n   k: String[1];\n}",
     "", ""),
    ("IncludesStore",
     "", "Database down::DownDB\n(\n   include up::DB\n\n"
     "   Table DOWN_ROW ( ROW_ID VARCHAR(20) PRIMARY KEY, RECORD_ID VARCHAR(20) )\n\n"
     "   Join Down_Up(DOWN_ROW.RECORD_ID = UP_RECORD.RECORD_ID)\n)", ""),
    ("IncludesMapping",
     "", "", "Mapping down::DownMapping\n(\n   include up::UpMapping\n)"),
    ("MapsUpstreamClass",
     "", "", "Mapping down::Remap\n(\n   up::BaseRecord[downSet]: Relational\n   {\n"
     "      ~primaryKey ( [up::DB]UP_RECORD.RECORD_ID )\n"
     "      ~mainTable [up::DB]UP_RECORD\n"
     "      recordId: [up::DB]UP_RECORD.RECORD_ID\n   }\n)"),
    ("ExtendsUpstreamSet",
     "Class down::Narrow extends up::BaseRecord\n{\n}",
     "", "Mapping down::NarrowMapping\n(\n   include up::UpMapping\n\n"
     "   down::Narrow[downNarrow] extends [up_BaseRecord]: Relational\n   {\n   }\n)"),
    ("UpstreamEnumTransformer",
     "Class down::Scored\n{\n   k: String[1];\n   grade: up::Grade[1];\n}",
     "", "Mapping down::ScoreMapping\n(\n   include up::UpMapping\n\n"
     "   down::Scored: Relational\n   {\n"
     "      ~primaryKey ( [up::DB]UP_RECORD.RECORD_ID )\n"
     "      ~mainTable [up::DB]UP_RECORD\n"
     "      k: [up::DB]UP_RECORD.RECORD_ID,\n"
     "      grade: EnumerationMapping GradeMapping: [up::DB]UP_RECORD.GRADE\n   }\n)"),
]


def build(name, pure, rel, mapping) -> str:
    out = [UPSTREAM]
    if pure:
        out.append("###Pure\n" + pure + "\n")
    if rel:
        out.append("###Relational\n" + rel + "\n")
    if mapping:
        out.append("###Mapping\n" + mapping + "\n")
    return "\n".join(out)


def main() -> None:
    work = Path(tempfile.mkdtemp())
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    for name, pure, rel, mapping in FORMS:
        src = work / f"{name}.pure"
        src.write_text(build(name, pure, rel, mapping))
        r = subprocess.run(
            [f"{runner.JAVA_HOME}/bin/java", "-cp",
             f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)],
            capture_output=True, text=True,
            env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
            cwd=runner.RUNNER, timeout=900)
        out = r.stdout + r.stderr
        err = re.search(r"(?:EngineException|Exception)[:\s]+([^\n]{0,110})", out)
        print(f"  {'FAILS   ' if err else 'compiles'}  {name:<24}"
              f"{err.group(1).strip() if err else ''}")
    print(f"\n  sources kept at {work}")


if __name__ == "__main__":
    main()
