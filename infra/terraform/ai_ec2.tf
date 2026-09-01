resource "aws_security_group" "ai_server" {
  name        = "jobai-ai-server-sg"
  description = "Security group for JobAI FastAPI AI server"
  vpc_id      = aws_vpc.jobai.id

  ingress {
    description     = "allow FastAPI from Spring EC2 security group"
    from_port       = 8001
    to_port         = 8001
    protocol        = "tcp"
    security_groups = [aws_security_group.jobai.id]
  }

  ingress {
    description = "allow SSH from admin IP"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["${var.my_ip}/32"]
  }

  ingress {
    description     = "node_exporter scrape from monitoring instance only"
    from_port       = 9100
    to_port         = 9100
    protocol        = "tcp"
    security_groups = [aws_security_group.monitoring.id]
  }

  # 개발 중 임시 허용. 팀 작업이 끝나면 ai_server_dev_ips를 비워 다시 닫는다.
  dynamic "ingress" {
    for_each = length(var.ai_server_dev_ips) > 0 ? [1] : []

    content {
      description = "temporary direct FastAPI access for developer machines"
      from_port   = 8001
      to_port     = 8001
      protocol    = "tcp"
      cidr_blocks = [for ip in var.ai_server_dev_ips : "${ip}/32"]
    }
  }

  egress {
    description = "allow HTTPS egress for ECR, S3, SSM, and external APIs"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 로그 수집기가 모니터링 인스턴스의 Loki로 전송한다. VPC 내부로만 나간다.
  egress {
    description = "ship logs to Loki inside the VPC"
    from_port   = 3100
    to_port     = 3100
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.jobai.cidr_block]
  }

  tags = {
    Name = "jobai-ai-server-sg"
  }
}

resource "aws_iam_role" "ai_ec2" {
  name = "jobai-ai-ec2-role"

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
    Name = "jobai-ai-ec2-role"
  }
}

resource "aws_iam_policy" "ai_ec2_ecr_read" {
  name        = "jobai-ai-ec2-ecr-read"
  description = "Allow AI EC2 to pull only the JobAI AI server image from ECR"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:DescribeImages",
          "ecr:DescribeRepositories",
          "ecr:GetDownloadUrlForLayer"
        ]
        Resource = aws_ecr_repository.ai_server.arn
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ai_ec2_ecr_read" {
  role       = aws_iam_role.ai_ec2.name
  policy_arn = aws_iam_policy.ai_ec2_ecr_read.arn
}

resource "aws_iam_role_policy_attachment" "ai_ec2_ssm_managed_instance" {
  role       = aws_iam_role.ai_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ai_ec2_model_s3_read" {
  role       = aws_iam_role.ai_ec2.name
  policy_arn = aws_iam_policy.ec2_ai_model_s3_read.arn
}

resource "aws_iam_instance_profile" "ai_ec2" {
  name = "jobai-ai-ec2-instance-profile"
  role = aws_iam_role.ai_ec2.name
}

resource "aws_instance" "ai_server" {
  ami                    = var.ami_id
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.jobai_public.id
  vpc_security_group_ids = [aws_security_group.ai_server.id]
  key_name               = var.key_name
  iam_instance_profile   = aws_iam_instance_profile.ai_ec2.name

  user_data = <<-EOF
    #!/bin/bash
    set -e
    sed -i 's|http://|https://|g' /etc/apt/sources.list
    apt-get update -y
    apt-get install -y docker.io awscli snapd
    systemctl start docker
    systemctl enable docker
    usermod -aG docker ubuntu
    if [ ! -f /swapfile ]; then
      fallocate -l 4G /swapfile
      chmod 600 /swapfile
      mkswap /swapfile
      swapon /swapfile
      echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
    snap install amazon-ssm-agent --classic || true
    systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
    systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true
    curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose
    docker run -d --name jobai-node-exporter --restart unless-stopped \
      --network host --pid host \
      -v /:/host:ro,rslave \
      prom/node-exporter:v1.8.2 --path.rootfs=/host
  EOF

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    encrypted   = true
    volume_type = "gp3"
    volume_size = 30
  }

  lifecycle {
    ignore_changes = [user_data]
  }

  tags = {
    Name = "jobai-ai-server-ec2"
  }
}
