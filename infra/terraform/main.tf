terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# VPC
resource "aws_vpc" "jobai" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "jobai-vpc"
  }
}

# 서브넷
resource "aws_subnet" "jobai_public" {
  vpc_id                  = aws_vpc.jobai.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true

  tags = {
    Name = "jobai-public-subnet"
  }
}

# 두 번째 퍼블릭 서브넷 (RDS DB 서브넷 그룹을 위한 서로 다른 AZ)
resource "aws_subnet" "jobai_public_2" {
  vpc_id                  = aws_vpc.jobai.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "ap-northeast-2b"
  map_public_ip_on_launch = true

  tags = {
    Name = "jobai-public-subnet-2"
  }
}

# 인터넷 게이트웨이
resource "aws_internet_gateway" "jobai" {
  vpc_id = aws_vpc.jobai.id

  tags = {
    Name = "jobai-igw"
  }
}

# 라우팅 테이블
resource "aws_route_table" "jobai_public" {
  vpc_id = aws_vpc.jobai.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.jobai.id
  }

  tags = {
    Name = "jobai-public-rt"
  }
}

resource "aws_route_table_association" "jobai_public" {
  subnet_id      = aws_subnet.jobai_public.id
  route_table_id = aws_route_table.jobai_public.id
}

resource "aws_route_table_association" "jobai_public_2" {
  subnet_id      = aws_subnet.jobai_public_2.id
  route_table_id = aws_route_table.jobai_public.id
}

# 보안그룹
resource "aws_security_group" "jobai" {
  name        = "jobai-sg"
  description = "jobai security group"
  vpc_id      = aws_vpc.jobai.id

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["${var.my_ip}/32"] # SSH는 내 IP만
  }

  ingress {
    description = "allow http for certbot"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "allow https"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # Spring Boot 전체 오픈
  }

  ingress {
    from_port   = 8001
    to_port     = 8001
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # FastAPI 전체 오픈
  }

  ingress {
    description     = "actuator/prometheus scrape from monitoring instance only"
    from_port       = 9090
    to_port         = 9090
    protocol        = "tcp"
    security_groups = [aws_security_group.monitoring.id]
  }

  ingress {
    description     = "node_exporter scrape from monitoring instance only"
    from_port       = 9100
    to_port         = 9100
    protocol        = "tcp"
    security_groups = [aws_security_group.monitoring.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "jobai-sg"
  }
}

# EC2
resource "aws_instance" "jobai" {
  ami                    = var.ami_id
  instance_type          = "t3.small"
  subnet_id              = aws_subnet.jobai_public.id
  vpc_security_group_ids = [aws_security_group.jobai.id]
  key_name               = var.key_name
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  user_data = <<-EOF
    #!/bin/bash
    apt-get update -y
    apt-get install -y docker.io awscli snapd nginx
    # t3.micro(1GB)에서 Spring Boot JVM 기동 중 OOM으로 죽지 않도록 스왑 확보
    if [ ! -f /swapfile ]; then
      fallocate -l 2G /swapfile
      chmod 600 /swapfile
      mkswap /swapfile
      swapon /swapfile
      echo '/swapfile none swap sw 0 0' >> /etc/fstab
    elif ! swapon --show=NAME --noheadings | grep -qx /swapfile; then
      swapon /swapfile
    fi
    systemctl start docker
    systemctl enable docker
    usermod -aG docker ubuntu
    systemctl enable nginx
    systemctl start nginx
    snap install amazon-ssm-agent --classic || true
    systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
    systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true
    curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose
  EOF

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2 # 컨테이너에서 브리지 네트워크를 거쳐 IMDS에 접근하려면 홉 1개가 더 필요함
  }

  root_block_device {
    encrypted   = true
    volume_type = "gp3"
    volume_size = 30
  }

  tags = {
    Name = "jobai-ec2"
  }
}

resource "aws_db_subnet_group" "jobai" {
  name       = "jobai-db-subnet-group"
  subnet_ids = [aws_subnet.jobai_public.id, aws_subnet.jobai_public_2.id]

  tags = {
    Name = "jobai-db-subnet-group"
  }
}

resource "aws_security_group" "rds" {
  name        = "jobai-rds-sg"
  description = "Postgres access for jobai application"
  vpc_id      = aws_vpc.jobai.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.jobai.id]
  }

  # 제한된 아웃바운드 규칙: 불필요한 전체 공개 egress 제거
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [aws_vpc.jobai.cidr_block]
  }

  tags = {
    Name = "jobai-rds-sg"
  }
}

resource "aws_db_instance" "jobai" {
  identifier                 = "jobai-db"
  allocated_storage          = var.db_allocated_storage
  engine                     = var.db_engine
  engine_version             = var.db_engine_version
  instance_class             = var.db_instance_class
  db_name                    = var.db_name
  username                   = var.db_username
  password                   = var.db_password
  port                       = var.db_port
  db_subnet_group_name       = aws_db_subnet_group.jobai.name
  vpc_security_group_ids     = [aws_security_group.rds.id]
  skip_final_snapshot        = true
  publicly_accessible        = false
  storage_type               = "gp3"
  storage_encrypted          = true
  backup_retention_period    = var.db_backup_retention_period
  deletion_protection        = false
  apply_immediately          = true
  auto_minor_version_upgrade = true

  tags = {
    Name = "jobai-rds"
  }
}
