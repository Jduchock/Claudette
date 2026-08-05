# MFCS Schema Explorer

Tooling to connect to **Oracle MFCS (Merchandising Foundation Cloud Service) – PRD1**
and discover the JSON schema of every GET endpoint in the Postman collection
`PRD1 - John's Production Swagger`.

## What it does
1. Authenticates with Oracle IDCS using OAuth2 **client_credentials**.
2. Reads every **GET** endpoint (87 of them) straight from the Postman collection.
3. Calls each one, **capped at `limit=1`** so we never pull large data sets
   (some endpoints hold millions of rows).
4. Saves the raw JSON and an inferred field/type **schema** for each endpoint.
5. Writes a human-readable `_report.md` and machine-readable `_index.json`.

## Connection facts (from the collection)
| Setting | Value |
|---|---|
| Base URL | `https://rex.retail.us-ashburn-1.ocs.oraclecloud.com/rgbu-rex-hibb-prd1-mfcs/MerchIntegrations` |
| Token URL | `https://idcs-2abd844087be40dfbd508ed0155fb1e6.identity.oraclecloud.com/oauth2/v1/token` |
| Grant | `client_credentials` |
| Scope | `rgbu:merch:MFCS-PRD1` |

## Setup
```bash
pip install -r requirements.txt
# edit .env and fill in MFCS_CLIENT_ID and MFCS_CLIENT_SECRET
```

## Run
```bash
# probe every GET endpoint, one record each
python explore_mfcs_schema.py --collection "../PRD1 - Johns Production Swagger.postman_collection.json"

# also retry the /:id endpoints using ids harvested from list endpoints
python explore_mfcs_schema.py --collection "../PRD1 - Johns Production Swagger.postman_collection.json" --two-pass

# only a subset (name/folder substring)
python explore_mfcs_schema.py --collection "..." --only item
```

## Output (./schemas/)
- `raw/<endpoint>.json` – raw response body
- `schema/<endpoint>.json` – inferred field → type map
- `_report.md` – readable per-endpoint report
- `_index.json` – full run summary

## Notes
- 57 of the 87 GETs support a `limit` query param → we send `limit=1`.
- Endpoints with a `/:id` path segment need a sample id; pass `--two-pass` to
  let the script reuse ids harvested from list endpoints, or supply them manually.
- `.env` holds secrets and is git-ignored.
