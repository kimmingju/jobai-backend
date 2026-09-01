# Prometheus + Grafana 관측 스택 전용 인스턴스.
# 백엔드 EC2(t3.micro, 1GB)는 이미 스왑을 쓸 만큼 여유가 없어 같은 호스트에 올리지 않는다.
#
# 설정 파일은 user_data 16KB 한도를 넘기므로 S3에 올려두고 부팅 시 sync 한다.
# Slack 웹훅은 저장소에 두지 않고 SSM Parameter Store(SecureString)에서 읽는다.

locals {
  monitoring_config_bucket = "jobai-monitoring-config-${data.aws_caller_identity.current.account_id}-${var.aws_region}"
  monitoring_param_path    = "/jobai/prod/monitoring"
}

resource "aws_s3_bucket" "monitoring_config" {
  bucket        = local.monitoring_config_bucket
  force_destroy = true

  tags = {
    Name = "jobai-monitoring-config"
  }
}

resource "aws_s3_bucket_public_access_block" "monitoring_config" {
  bucket = aws_s3_bucket.monitoring_config.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "monitoring_config" {
  bucket = aws_s3_bucket.monitoring_config.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ── 모니터링 스택 설정 파일 ────────────────────────────────────────

resource "aws_s3_object" "monitoring_compose" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "docker-compose.yml"
  content     = file("${path.module}/../monitoring/docker-compose.yml")
  source_hash = filemd5("${path.module}/../monitoring/docker-compose.yml")
}

resource "aws_s3_object" "prometheus_config" {
  bucket = aws_s3_bucket.monitoring_config.id
  key    = "prometheus/prometheus.yml"

  content = templatefile("${path.module}/../prometheus/prometheus-prod.yml.tftpl", {
    backend_private_ip = aws_instance.jobai.private_ip
    ai_private_ip      = aws_instance.ai_server.private_ip
  })
}

resource "aws_s3_object" "loki_config" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "loki/loki-config.yml"
  content     = file("${path.module}/../loki/loki-config.yml")
  source_hash = filemd5("${path.module}/../loki/loki-config.yml")
}

resource "aws_s3_object" "grafana_datasource" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "grafana/provisioning/datasources/datasource.yml"
  content     = file("${path.module}/../grafana/provisioning/datasources/datasource.yml")
  source_hash = filemd5("${path.module}/../grafana/provisioning/datasources/datasource.yml")
}

resource "aws_s3_object" "grafana_dashboard_provider" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "grafana/provisioning/dashboards/dashboard.yml"
  content     = file("${path.module}/../grafana/provisioning/dashboards/dashboard.yml")
  source_hash = filemd5("${path.module}/../grafana/provisioning/dashboards/dashboard.yml")
}

resource "aws_s3_object" "grafana_contact_points" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "grafana/provisioning/alerting/contact-points.yml"
  content     = file("${path.module}/../grafana/provisioning/alerting/contact-points.yml")
  source_hash = filemd5("${path.module}/../grafana/provisioning/alerting/contact-points.yml")
}

resource "aws_s3_object" "grafana_notification_policies" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "grafana/provisioning/alerting/notification-policies.yml"
  content     = file("${path.module}/../grafana/provisioning/alerting/notification-policies.yml")
  source_hash = filemd5("${path.module}/../grafana/provisioning/alerting/notification-policies.yml")
}

resource "aws_s3_object" "grafana_alert_rules" {
  bucket      = aws_s3_bucket.monitoring_config.id
  key         = "grafana/provisioning/alerting/rules.yml"
  content     = file("${path.module}/../grafana/provisioning/alerting/rules.yml")
  source_hash = filemd5("${path.module}/../grafana/provisioning/alerting/rules.yml")
}

# 대시보드 쿼리는 로컬 검증용 job 이름을 쓰므로 프로덕션 job 이름으로 바꿔 올린다.
resource "aws_s3_object" "grafana_dashboard" {
  bucket  = aws_s3_bucket.monitoring_config.id
  key     = "grafana/dashboards/jobai-backend-overview.json"
  content = replace(file("${path.module}/../grafana/dashboards/jobai-backend-overview.json"), "jobai-backend-local", "jobai-backend")
}

# ── 보안그룹 ───────────────────────────────────────────────────────

resource "aws_security_group" "monitoring" {
  name        = "jobai-monitoring-sg"
  description = "Prometheus and Grafana for JobAI observability"
  vpc_id      = aws_vpc.jobai.id

  ingress {
    description = "Grafana UI from admin IP"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["${var.my_ip}/32"]
  }

  ingress {
    description = "Prometheus UI from admin IP"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = ["${var.my_ip}/32"]
  }

  ingress {
    description = "SSH from admin IP"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["${var.my_ip}/32"]
  }

  # 앱 호스트의 로그 수집기가 Loki로 push 한다.
  # 앱 보안그룹을 참조하면 순환 의존이 생기므로 VPC 내부로 제한한다.
  ingress {
    description = "Loki push from hosts inside the VPC"
    from_port   = 3100
    to_port     = 3100
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.jobai.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "jobai-monitoring-sg"
  }
}

# ── IAM ───────────────────────────────────────────────────────────

resource "aws_iam_role" "monitoring_ec2" {
  name = "jobai-monitoring-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = {
    Name = "jobai-monitoring-ec2-role"
  }
}

resource "aws_iam_role_policy_attachment" "monitoring_ssm_managed_instance" {
  role       = aws_iam_role.monitoring_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_policy" "monitoring_config_access" {
  name        = "jobai-monitoring-config-access"
  description = "Allow the monitoring instance to read its config from S3 and the Slack webhook from SSM"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.monitoring_config.arn
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.monitoring_config.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParametersByPath"]
        Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.monitoring_param_path}/*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "monitoring_config_access" {
  role       = aws_iam_role.monitoring_ec2.name
  policy_arn = aws_iam_policy.monitoring_config_access.arn
}

resource "aws_iam_instance_profile" "monitoring_ec2" {
  name = "jobai-monitoring-ec2-instance-profile"
  role = aws_iam_role.monitoring_ec2.name
}

# ── 인스턴스 ───────────────────────────────────────────────────────

resource "aws_instance" "monitoring" {
  ami                    = var.ami_id
  instance_type          = "t3.micro"
  subnet_id              = aws_subnet.jobai_public.id
  vpc_security_group_ids = [aws_security_group.monitoring.id]
  key_name               = var.key_name
  iam_instance_profile   = aws_iam_instance_profile.monitoring_ec2.name

  user_data = <<-EOF
    #!/bin/bash
    apt-get update -y
    apt-get install -y docker.io awscli snapd
    if [ ! -f /swapfile ]; then
      fallocate -l 2G /swapfile
      chmod 600 /swapfile
      mkswap /swapfile
      swapon /swapfile
      echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
    systemctl start docker
    systemctl enable docker
    usermod -aG docker ubuntu
    snap install amazon-ssm-agent --classic || true
    systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
    systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true
    curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose

    mkdir -p /opt/monitoring
    aws s3 sync "s3://${aws_s3_bucket.monitoring_config.id}/" /opt/monitoring/ --region ${var.aws_region}

    # 알림 채널이 바뀌어도 파라미터만 추가하면 되도록 경로 전체를 환경변수로 만든다.
    umask 077
    : > /opt/monitoring/.env
    aws ssm get-parameters-by-path --region ${var.aws_region} \
      --path "${local.monitoring_param_path}" --recursive --with-decryption \
      --query "Parameters[].[Name,Value]" --output text 2>/dev/null |
      while IFS=$'\t' read -r pname pvalue; do
        KEY=$(basename "$pname" | tr '[:lower:]' '[:upper:]')
        printf '%s=%s\n' "$KEY" "$pvalue" >> /opt/monitoring/.env
      done
    grep -q '^SLACK_WEBHOOK_URL='   /opt/monitoring/.env || echo 'SLACK_WEBHOOK_URL='   >> /opt/monitoring/.env
    grep -q '^DISCORD_WEBHOOK_URL=' /opt/monitoring/.env || echo 'DISCORD_WEBHOOK_URL=' >> /opt/monitoring/.env

    cd /opt/monitoring
    /usr/local/bin/docker-compose up -d
  EOF

  # 설정 파일이 바뀌었을 때 인스턴스를 다시 만들지 않고 재적용할 수 있도록,
  # 설정 변경은 SSM으로 s3 sync 후 compose 재기동하는 방식을 쓴다.
  lifecycle {
    ignore_changes = [user_data]
  }

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    encrypted   = true
    volume_type = "gp3"
    volume_size = 20
  }

  tags = {
    Name = "jobai-monitoring-ec2"
  }
}
