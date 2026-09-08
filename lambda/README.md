# Presigned-URL Lambda — deploy & wire up to the Java agent

## What this deploys

```
Customer PC (Java scheduler agent)
        |
        | HTTPS POST /upload/presigned-url
        | Authorization: Bearer <api-key>
        v
API Gateway (HTTP API)  <-- created by template.yaml
        |
        v
Lambda: psystem-generate-presigned-url
        |
        | validates Authorization header against Secrets Manager
        | validates request body
        | s3_client.generate_presigned_url(...)
        v
Returns { uploadUrl, bucket, objectKey, expiresIn, requiredHeaders }
```

No AWS SDK, no AWS credentials, and no IAM role are ever present on the customer PC —
the Java agent only ever speaks plain HTTPS to the API Gateway URL. All the AWS-side
permissions (`s3:PutObject`, `secretsmanager:GetSecretValue`) live on the Lambda's
execution role, scoped to this customer's S3 prefix only.

---

## 1. Prerequisites

- AWS SAM CLI installed (`pip install aws-sam-cli` or see AWS docs).
- An existing S3 bucket for uploads (the one already in your Java agent's
  `psystem.aws.bucket`).
- AWS CLI configured with credentials that can deploy CloudFormation/Lambda/API Gateway/
  Secrets Manager (this is a one-time deploy-time credential — never distributed to
  customer machines).

## 2. Deploy

```bash
cd lambda
sam build
sam deploy --guided
```

During `--guided`, you'll be prompted for:
- **Stack name**: e.g. `psystem-presigned-url-backend`
- **AWS Region**: e.g. `ap-south-1` (must match `psystem.aws.region` in the Java agent)
- **Parameter UploadBucketName**: your existing bucket, e.g. `my-backup-bucket`
- **Parameter CustomerPrefix**: e.g. `customer1` (must match what your object-key
  convention in the README expects)

`sam deploy` prints two useful outputs at the end:

```
Outputs
-----------------------------------------------------------------------------------
Key                 PresignedUrlEndpoint
Value                https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod/upload/presigned-url

Key                 ApiKeySecretArn
Value                arn:aws:secretsmanager:ap-south-1:111122223333:secret:psystem/presigned-url-api-key-AbCdEf
-----------------------------------------------------------------------------------
```

## 3. Retrieve the generated API key

```bash
aws secretsmanager get-secret-value \
  --secret-id psystem/presigned-url-api-key \
  --query SecretString --output text
```

## 4. Point the Java agent at it

Set these on the customer machine (environment variables, matching what
`application.yml` already reads — **no Java code changes needed**):

```bash
# Linux/macOS
export PSYSTEM_PRESIGNED_URL_API="https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod/upload/presigned-url"
export PSYSTEM_API_KEY="<value from step 3>"
```

```powershell
# Windows
setx PSYSTEM_PRESIGNED_URL_API "https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod/upload/presigned-url"
setx PSYSTEM_API_KEY "<value from step 3>"
```

Or edit `deploy/application-customer-example.yml` directly:

```yaml
psystem:
  upload:
    presigned-url-api: "https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod/upload/presigned-url"
    api-key: "${PSYSTEM_API_KEY}"
```

Restart the agent (or the systemd/Windows service). `PresignedUrlClient` in the agent
already sends `Authorization: Bearer <api-key>` on every request — this is exactly what
the Lambda now validates.

## 5. Verify end-to-end

```bash
curl -X POST "https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod/upload/presigned-url" \
  -H "Authorization: Bearer <api-key>" \
  -H "Content-Type: application/json" \
  -d '{
        "machineId": "MACHINE-001",
        "location": "Kolkata",
        "destinationPath": "/customer/backup",
        "fileName": "backup3.zip",
        "fileSize": 524288000,
        "fileType": "ZIP"
      }'
```

Expect a `200` with `uploadUrl`, `objectKey`, `expiresIn`, `requiredHeaders`. Then trigger
the Java agent manually (`POST http://127.0.0.1:8085/admin/trigger`) and watch its logs —
it should show `Pre-signed URL received` followed by `Upload successful`.

## 6. Local unit tests (no AWS account needed)

```bash
cd lambda
pip install -r requirements-dev.txt
pytest
```

Uses `moto` to mock S3, so `generate_presigned_url` and validation logic are tested
without any real AWS calls.

## 7. Redeploying after a code change

```bash
sam build && sam deploy
```

(No `--guided` needed after the first run — SAM remembers the parameters in
`samconfig.toml`.)

## Notes / things to revisit for a hardened production rollout

- **Auth is intentionally simple** (single shared API key validated inside the Lambda).
  For per-customer keys, key rotation, or rate limiting per customer, swap this for a
  proper Lambda authorizer, Cognito, or API Gateway usage plans + API keys.
- **CloudWatch Logs** are enabled by default for both the Lambda and API Gateway access
  logs (SAM's default) — check there first if requests aren't reaching S3 as expected.
- **CORS** is not configured since this endpoint is only ever called server-side by the
  Java agent, never from a browser.
