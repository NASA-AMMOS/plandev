#!/bin/sh

version="v0.5.3"
os=$(uname | tr '[A-Z]' '[a-z]')

# Create Assets
cd assets
cp ../../examples/banananation/build/libs/banananation.jar .
# Generate BaseURLs file
cp ../../e2e-tests/src/test/java/gov/nasa/jpl/plandev/e2e/utils/BaseURL.java urls.ts

# Remove blank lines
sed -e '/^\s*$/d' urls.ts > temp && mv temp urls.ts
# Reduce to enums
sed -e '1,2d' urls.ts > temp && mv temp urls.ts
sed -e :a -e '$d;N;2,5ba' -e 'P;D' urls.ts > temp && mv temp urls.ts
# Convert from Java Enum to TS Constants
sed -e 's.^[ \t]*.export const .g' \
    -e 's.),.;.g' \
    -e 's.);.;.g' \
    -e 's.(._URL = .g' \
    urls.ts > temp && mv temp urls.ts

# Generate GQL queries file
cp ../../e2e-tests/src/test/java/gov/nasa/jpl/plandev/e2e/utils/GQL.java gql.ts

# Remove blank lines
sed -e '/^\s*$/d' gql.ts > temp && mv temp gql.ts
# Reduce to enum
sed -e '1d' gql.ts > temp && mv temp gql.ts
sed -e :a -e '$d;N;2,5ba' -e 'P;D' gql.ts > temp && mv temp gql.ts
# Convert from Java Enum to TS Enum
sed -e 's.public enum GQL.const gql = .g' \
    -e 's.(""".: `#graphql.g' \
    -e 's.""");*.`.g' \
    gql.ts > temp && mv temp gql.ts
echo "\n};\nexport default gql;" >> gql.ts

cd ..

npm install && npm run build

curl -L "https://github.com/grafana/xk6-dashboard/releases/download/${version}/xk6-dashboard_${version}_${os}_amd64.tar.gz" | tar xvz k6

./k6 run \
    --out "json=load-report.json" \
    --out "dashboard=period=1s&report=load-report.html" \
    ./dist/load-test.js
./k6 run \
    --out "json=load-report.json" \
    --out "dashboard=period=1s&report=load-report.html" \
    ./dist/db-lockup-test.js
