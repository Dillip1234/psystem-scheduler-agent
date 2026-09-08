"""
Run with: pytest test_presigned_url_lambda.py
Requires: pip install pytest moto boto3
"""
import json
import os

import boto3
import pytest
from moto import mock_aws

os.environ.setdefault("UPLOAD_BUCKET", "my-backup-bucket")
os.environ.setdefault("AWS_REGION", "ap-south-1")
os.environ.setdefault("CUSTOMER_PREFIX", "customer1")
os.environ.setdefault("AWS_DEFAULT_REGION", "ap-south-1")

VALID_PAYLOAD = {
    "machineId": "MACHINE-001",
    "location": "Kolkata",
    "destinationPath": "/customer/backup",
    "fileName": "backup3.zip",
    "fileSize": 524288000,
    "fileType": "ZIP",
    "executionId": "8f123abc",
}


def make_event(payload):
    return {"body": json.dumps(payload), "isBase64Encoded": False}


@pytest.fixture
def lambda_module():
    with mock_aws():
        boto3.client("s3", region_name="ap-south-1").create_bucket(
            Bucket="my-backup-bucket",
            CreateBucketConfiguration={"LocationConstraint": "ap-south-1"},
        )
        import importlib
        import presigned_url_lambda
        importlib.reload(presigned_url_lambda)  # re-init s3_client under the mock
        yield presigned_url_lambda


def test_returns_200_with_presigned_url(lambda_module):
    response = lambda_module.lambda_handler(make_event(VALID_PAYLOAD), None)

    assert response["statusCode"] == 200
    body = json.loads(response["body"])
    assert body["uploadUrl"].startswith("https://")
    assert body["objectKey"].startswith("customer1/MACHINE-001/")
    assert body["objectKey"].endswith("backup3.zip")
    assert body["expiresIn"] == 900
    assert body["requiredHeaders"]["x-amz-meta-machine-id"] == "MACHINE-001"


def test_rejects_missing_required_field(lambda_module):
    payload = dict(VALID_PAYLOAD)
    del payload["fileName"]

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 400
    assert "fileName" in json.loads(response["body"])["message"]


def test_rejects_negative_file_size(lambda_module):
    payload = dict(VALID_PAYLOAD, fileSize=-5)

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 400


def test_rejects_unsupported_file_type(lambda_module):
    payload = dict(VALID_PAYLOAD, fileType="TAR", fileName="backup.tar")

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 400


def test_rejects_path_traversal_in_filename(lambda_module):
    payload = dict(VALID_PAYLOAD, fileName="../../etc/passwd.zip")

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 400


def test_rejects_malformed_json_body(lambda_module):
    event = {"body": "{not valid json", "isBase64Encoded": False}

    response = lambda_module.lambda_handler(event, None)

    assert response["statusCode"] == 400


def test_object_key_encodes_machine_id_and_date(lambda_module):
    response = lambda_module.lambda_handler(make_event(VALID_PAYLOAD), None)

    body = json.loads(response["body"])
    parts = body["objectKey"].split("/")
    assert parts[0] == "customer1"
    assert parts[1] == "MACHINE-001"
    assert len(parts) == 6  # customer1/MACHINE-001/YYYY/MM/DD/filename


def test_uses_customer_prefix_from_request_body(lambda_module):
    # This is the point of the change: the prefix comes from the agent
    # (psystem.customer.prefix), not from this Lambda's own CUSTOMER_PREFIX env var.
    payload = dict(VALID_PAYLOAD, customerPrefix="acme-logistics")

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 200
    body = json.loads(response["body"])
    assert body["objectKey"].startswith("acme-logistics/MACHINE-001/")


def test_falls_back_to_env_default_when_prefix_omitted(lambda_module):
    # Backward compatibility: an agent build that predates customerPrefix still works,
    # landing under DEFAULT_CUSTOMER_PREFIX (the CUSTOMER_PREFIX env var) instead of failing.
    payload = dict(VALID_PAYLOAD)
    payload.pop("customerPrefix", None)

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 200
    body = json.loads(response["body"])
    assert body["objectKey"].startswith("customer1/MACHINE-001/")


def test_rejects_customer_prefix_with_unsafe_characters(lambda_module):
    payload = dict(VALID_PAYLOAD, customerPrefix="../etc")

    response = lambda_module.lambda_handler(make_event(payload), None)

    assert response["statusCode"] == 400
    assert "customerPrefix" in json.loads(response["body"])["message"]
