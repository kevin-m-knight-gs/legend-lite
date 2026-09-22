import os,sys,zipfile,hashlib,json
M=os.path.expanduser("~/.m2/repository/org/finos/legend")
# map: jar -> resource-root prefix inside jar (everything after src/main/resources)
JARS={
 "engine-core-pure": f"{M}/engine/legend-engine-pure-code-compiled-core/4.145.0/legend-engine-pure-code-compiled-core-4.145.0.jar",
 "relational-core-pure": f"{M}/engine/legend-engine-xt-relationalStore-core-pure/4.145.0/legend-engine-xt-relationalStore-core-pure-4.145.0.jar",
}
for name,j in JARS.items():
    if not os.path.exists(j): print("MISSING JAR",name,j); continue
    z=zipfile.ZipFile(j)
    pure=[n for n in z.namelist() if n.endswith('.pure')]
    print(f"{name}: {len(pure)} .pure entries")
