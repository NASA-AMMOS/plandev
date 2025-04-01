#!/bin/bash

mkdir results
mkdir comparison

PGCMPINPUT1=./pgdumpmigrated/AerieMigratedUp \
PGCMPINPUT2=./pgdumpcurrent/AerieCurrent \
PGCLABEL1=AerieMigratedUp \
PGCLABEL2=AerieCurrent \
PGCFULLOUTPUT=./comparison/fulloutput.txt \
PGCUNEXPLAINED=./comparison/unexplained.txt \
PGCBADEXPLAIN=./comparison/badexplanations.txt \
PGDB=postgres \
PGBINDIR=/usr/bin \
PGCOMITSCHEMAS="('hdb_catalog'),('pg_catalog'),('information_schema')" \
PGCEXPLANATIONS=./explanations \
./pgcmp
return_code=$?

if [ $return_code -ne 0 ]; then
  echo "Database schema comparison failed - return code=$return_code"
  mv ./comparison/fulloutput.txt results/fulloutput
  mv ./comparison/unexplained.txt results/unexplained
  mv ./comparison/badexplanations.txt results/badexplanations
  mv /tmp/perform-comparison.log results/perform-comparison.log
else
  echo "Database schema comparison succeeded"
fi

exit $return_code
