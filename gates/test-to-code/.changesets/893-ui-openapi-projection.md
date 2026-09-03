---
classification: declared-regression
tempdoc: 893
---
The UI module's ratio moves from 913 to 912 because the classified OpenAPI projection adds the
renderer, offline snapshot exporter, route digest, and build wiring in production sources. The same
module adds focused renderer and exporter tests plus route-manifest coverage; the small ratio decrease
is the result of substantive implementation size, not missing tests or test removal. The baseline is
repinned to the measured value in this changeset's commit.
