# Claudette / MFCS PRD1 — Test Store Selection

**Source:** Oracle MFCS PRD1 `GET /services/foundation/store` (1,309 stores pulled; 999 open; 963 real physical retail stores after excluding virtual/e-com "District 99" records). Cluster chosen by geocoding every real store to its ZIP centroid and finding the tightest 5-store group chain-wide.

## The 5 "main" test stores — Birmingham, AL metro

All five are in the Birmingham, AL metro (Jefferson County), all **District 25 → Region 1 → Division 1 (Area) → Hibbett Retail (Chain 301) → Hibbett & City Gear (Company)**, all Central time, all channel **BM / Hibbett**, all store type **C (Company-owned)**. Maximum distance between any two is **6.4 miles**; stores #33 and #54 share the same building, as do #966 and #1511 (same ZIP).

| Store # | Name | Address | City / ZIP | County | Type | Format | Class | Sq Ft | Opened |
|---|---|---|---|---|---|---|---|---|---|
| **33** | H -WESTERN HILLS | 7201 Aaron Aronov Dr, Ste 13C | Fairfield, AL 35064 | Jefferson | C | Fashion 1 (FS1) | D | 5,300 | 1986-02-01 |
| **54** | SA-WEST.HILLS (Sports Additions) | 7201 Aaron Aronov Dr, Ste 9A | Fairfield, AL 35064 | Jefferson | C | Fashion 1 (FS1) | D | 1,530 | 1993-05-22 |
| **513** | H -BIRMINGHAM, AL (5 Pts W) | 2245 Bessemer Road | Birmingham, AL 35208 | Jefferson | C | Fashion 1 (FS1) | D | 4,086 | 2005-01-29 |
| **966** | H -BIRMINGHAM AL (Palisades) | 352 Palisades Blvd | Birmingham, AL 35209 | Jefferson | C | Fashion 1 (FS1) | D | 5,191 | 2010-12-10 |
| **1511** | H -HOMEWOOD AL (Wildwood) | 225 Lakeshore Pkwy, 253-101 | Homewood, AL 35209 | Jefferson | C | Fashion 2 (FS2) | E | 6,269 | 2022-01-25 |

### Hierarchy (identical for all 5)
```
Company 1  Hibbett & City Gear
 └ Chain 301  Hibbett Retail
    └ Area 1  Division 1
       └ Region 1
          └ District 25
             └ Stores 33, 54, 513, 966, 1511
```

### Pairwise distances (ZIP-centroid, miles)
```
        #33   #54   #513  #966  #1511
#33      —    0.0   2.8   6.4   6.4
#54            —    2.8   6.4   6.4
#513                 —    4.7   4.7
#966                       —    0.0
#1511                            —
```

### Why this cluster
- **Tightest real-store group in the entire chain** (6.4 mi diameter) among 963 physical stores.
- **One clean hierarchy branch** (single district/region/area) — good for testing hierarchy-scoped behavior without cross-district noise.
- **Format & class variety** within the tight radius: four FS1 + one FS2, classes D and E, sizes from 1,530 to 6,269 sq ft, and a small-format **Sports Additions** banner (#54) alongside standard Hibbett stores — useful for varied test cases.
- Home market, so data is dense and well-maintained (all opened 1986–2022, none closed).

## Outlier (negative-test) store — *reserved for you*
Per your note, I have **not** picked the outlier. When you're ready, candidates for a deliberately "far / different" store include a distant metro in another region/time zone (e.g., Las Vegas NV, Cleveland OH), an e-com/virtual **District 99** record, or a franchise/City Gear banner store — tell me the flavor of negative case you want and I'll pull specifics.

*Note: distances use ZIP-code centroids (MFCS store records carry full addresses but no lat/long). Exact street-level distances will vary by a mile or so but the ranking holds.*
