"""
GeneratePresignedUrl Lambda.

Sits behind API Gateway (HTTP API) at POST /upload/presigned-url.
Matches the contract expected by com.psystem.service.presignedurl.PresignedUrlClient
in the Java scheduler agent.

Request body (from the agent):
{
  "machineId": "MACHINE-001",
  "customerPrefix": "customer1",
  "location": "Kolkata",
  "destinationPath": "/customer/backup",
  "fileName": "backup3.zip",
  "fileSize": 524288000,
  "fileType": "ZIP",
  "executionId": "8f123abc"
}

"customerPrefix" is the S3 key prefix (see psystem.customer.prefix in the agent's
application.yml) - it comes from the agent, not from this Lambda's own configuration,
since one deployment of this Lambda can serve many customer agents, each with a
different prefix. CUSTOMER_PREFIX (env var, see below) is kept only as a fallback
default for agents that haven't been upgraded to send it yet.

Response body:
{
  "uploadUrl": "https://...",
  "bucket": "my-backup-bucket",
  "objectKey": "customer1/MACHINE-001/2026/08/13/backup3.zip",
  "expiresIn": 900,
  "requiredHeaders": {
    "x-amz-meta-machine-id": "MACHINE-001",
    "x-amz-meta-location": "Kolkata",
    "x-amz-meta-destination-path": "/customer/backup"
  }
}
"""

import json
import logging
import os
import re
import time

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

BUCKET = os.environ["UPLOAD_BUCKET"]
REGION = os.environ.get("AWS_REGION", "ap-south-1")
EXPIRES_IN_SECONDS = int(os.environ.get("PRESIGNED_URL_EXPIRES_IN", "900"))
# Fallback only, used when an older agent doesn't yet send "customerPrefix" in the
# request body. New/updated agents always send it (psystem.customer.prefix), and that
# value takes priority - see _resolve_customer_prefix().
DEFAULT_CUSTOMER_PREFIX = os.environ.get("CUSTOMER_PREFIX", "customer1")
API_KEY_SECRET_ARN = os.environ.get("API_KEY_SECRET_ARN")  # unset => auth check skipped (e.g. local testing)

# Use SigV4 explicitly and a regional endpoint - avoids surprises with older signature
# versions and virtual-hosted-vs-path-style URL differences across regions.
s3_client = boto3.client(
    "s3",
    region_name=REGION,
    config=Config(signature_version="s3v4"),
)
secrets_client = boto3.client("secretsmanager", region_name=REGION)

_cached_api_key = None  # cached for the lifetime of the execution environment (warm starts)

REQUIRED_FIELDS = ["machineId", "location", "destinationPath", "fileName", "fileSize", "fileType"]
ALLOWED_FILE_TYPES = {"ZIP", "RAR"}

# Conservative allow-list for machine id / file name to keep S3 keys predictable and to
# reject anything that looks like a path-traversal or key-injection attempt.
SAFE_TOKEN = re.compile(r"^[A-Za-z0-9._\-]+$")


def lambda_handler(event, context):
    try:
        if not _is_authorized(event):
            logger.warning("Rejected request with missing/invalid Authorization header")
            return _response(401, {"error": "UNAUTHORIZED", "message": "Invalid or missing API key"})

        body = _parse_body(event)
        _validate(body)

        machine_id = body["machineId"]
        customer_prefix = _resolve_customer_prefix(body)
        location = body["location"]
        destination_path = body["destinationPath"]
        file_name = body["fileName"]
        file_size = int(body["fileSize"])
        file_type = body["fileType"].upper()

        date_path = time.strftime("%Y/%m/%d")
        object_key = f"{customer_prefix}/{machine_id}/{date_path}/{file_name}"

        metadata = {
            "machine-id": machine_id,
            "location": location,
            "destination-path": destination_path,
        }

        presigned_url = s3_client.generate_presigned_url(
            ClientMethod="put_object",
            Params={
                "Bucket": BUCKET,
                "Key": object_key,
                "ContentType": "application/octet-stream",
                "Metadata": metadata,
                "ServerSideEncryption": "AES256",
            },
            ExpiresIn=EXPIRES_IN_SECONDS,
            HttpMethod="PUT",
        )

        logger.info(
            "Issued presigned URL. executionId=%s machineId=%s customerPrefix=%s objectKey=%s fileSize=%s",
            body.get("executionId"), machine_id, customer_prefix, object_key, file_size,
        )

        response_body = {
            "uploadUrl": presigned_url,
            "bucket": BUCKET,
            "objectKey": object_key,
            "expiresIn": EXPIRES_IN_SECONDS,
            "requiredHeaders": {
                "x-amz-meta-machine-id": machine_id,
                "x-amz-meta-location": location,
                "x-amz-meta-destination-path": destination_path,
                "x-amz-server-side-encryption": "AES256",
            },
        }

        return _response(200, response_body)

    except ValidationError as e:
        logger.warning("Validation error: %s", e)
        return _response(400, {"error": "VALIDATION_ERROR", "message": str(e)})

    except ClientError as e:
        # AWS-side failure generating the URL (e.g. bad bucket config) - surfaces as a
        # 502 so the agent's retry-with-backoff logic (5xx) kicks in correctly.
        logger.error("AWS ClientError generating presigned URL: %s", e)
        return _response(502, {"error": "UPSTREAM_ERROR", "message": "Failed to generate upload URL"})

    except Exception as e:  # noqa: BLE001 - top-level handler must not leak stack traces
        logger.exception("Unexpected error")
        return _response(500, {"error": "INTERNAL_ERROR", "message": "Unexpected server error"})


class ValidationError(Exception):
    pass


def _is_authorized(event):
    """Validates the Authorization: Bearer <key> header against Secrets Manager.

    If API_KEY_SECRET_ARN is not configured (e.g. local/dev testing), auth is skipped -
    this should never be the case in a real deployment; the SAM template always sets it.
    """
    if not API_KEY_SECRET_ARN:
        return True

    headers = event.get("headers", {}) or {}
    auth_header = headers.get("authorization") or headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return False
    token = auth_header[len("Bearer "):].strip()
    if not token:
        return False

    expected = _get_api_key()
    return token == expected


def _get_api_key():
    global _cached_api_key
    if _cached_api_key is None:
        response = secrets_client.get_secret_value(SecretId=API_KEY_SECRET_ARN)
        _cached_api_key = response["SecretString"]
    return _cached_api_key


def _parse_body(event):
    raw = event.get("body")
    if raw is None:
        raise ValidationError("Missing request body")
    if event.get("isBase64Encoded"):
        import base64
        raw = base64.b64decode(raw).decode("utf-8")
    try:
        return json.loads(raw)
    except json.JSONDecodeError as e:
        raise ValidationError(f"Malformed JSON body: {e}")


def _validate(body):
    missing = [f for f in REQUIRED_FIELDS if not body.get(f) and body.get(f) != 0]
    if missing:
        raise ValidationError(f"Missing required field(s): {', '.join(missing)}")

    if not isinstance(body["fileSize"], (int, float)) or body["fileSize"] <= 0:
        raise ValidationError("fileSize must be a positive number")

    if body["fileType"].upper() not in ALLOWED_FILE_TYPES:
        raise ValidationError(f"fileType must be one of {sorted(ALLOWED_FILE_TYPES)}")

    if not SAFE_TOKEN.match(body["machineId"]):
        raise ValidationError("machineId contains unsupported characters")

    customer_prefix = body.get("customerPrefix")
    if customer_prefix is not None and not SAFE_TOKEN.match(customer_prefix):
        raise ValidationError("customerPrefix contains unsupported characters")

    file_name = body["fileName"]
    if "/" in file_name or "\\" in file_name or ".." in file_name:
        raise ValidationError("fileName must not contain path separators")
    if not (file_name.lower().endswith(".zip") or file_name.lower().endswith(".rar")):
        raise ValidationError("fileName must end with .zip or .rar")


def _resolve_customer_prefix(body):
    """Agent-supplied "customerPrefix" wins; DEFAULT_CUSTOMER_PREFIX only covers
    requests from an older agent build that predates psystem.customer.prefix."""
    prefix = body.get("customerPrefix")
    return prefix if prefix else DEFAULT_CUSTOMER_PREFIX


def _response(status_code, body_dict):
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body_dict),
    }
