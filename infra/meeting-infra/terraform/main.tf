# Phase 8.6.6 — minimal Terraform stub.
#
# Real deployments wire this against an internal Vault provider for
# secrets so they never land in state. Resources here are the three
# the plan demands: managed Postgres, object-storage bucket, KMS key.
# State backend is intentionally omitted — choose `s3` or `gcs` per
# environment and configure via `terraform init -backend-config=...`.

terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "environment" {
  type        = string
  description = "Deployment environment (dev / staging / prod)."
}

variable "region" {
  type    = string
  default = "us-east-1"
}

provider "aws" {
  region = var.region
}

# ── Managed PostgreSQL ─────────────────────────────────────────────
resource "aws_db_instance" "meeting" {
  identifier        = "meeting-${var.environment}"
  engine            = "postgres"
  engine_version    = "15.5"
  instance_class    = "db.t3.medium"
  allocated_storage = 50

  db_name  = "meeting"
  username = "meeting"
  # Password comes from Vault — pipeline injects it via TF_VAR_db_password.
  password = var.db_password

  storage_encrypted       = true
  kms_key_id              = aws_kms_key.meeting.arn
  backup_retention_period = 7
  multi_az                = var.environment == "prod"
  deletion_protection     = var.environment == "prod"

  tags = {
    app         = "meeting"
    environment = var.environment
  }
}

variable "db_password" {
  type      = string
  sensitive = true
}

# ── Object storage bucket ──────────────────────────────────────────
resource "aws_s3_bucket" "meeting_exports" {
  bucket = "meeting-${var.environment}-exports"

  tags = {
    app         = "meeting"
    environment = var.environment
  }
}

resource "aws_s3_bucket_versioning" "meeting_exports" {
  bucket = aws_s3_bucket.meeting_exports.id

  versioning_configuration {
    status = var.environment == "prod" ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "meeting_exports" {
  bucket = aws_s3_bucket.meeting_exports.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.meeting.arn
    }
  }
}

# ── KMS key ────────────────────────────────────────────────────────
resource "aws_kms_key" "meeting" {
  description             = "Meeting Intelligence ${var.environment} master KMS key"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = {
    app         = "meeting"
    environment = var.environment
  }
}

resource "aws_kms_alias" "meeting" {
  name          = "alias/meeting-${var.environment}"
  target_key_id = aws_kms_key.meeting.key_id
}

# ── Outputs ────────────────────────────────────────────────────────
output "postgres_endpoint" {
  value = aws_db_instance.meeting.endpoint
}

output "exports_bucket" {
  value = aws_s3_bucket.meeting_exports.id
}

output "kms_key_arn" {
  value = aws_kms_key.meeting.arn
}
