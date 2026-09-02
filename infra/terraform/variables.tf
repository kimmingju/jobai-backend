variable "aws_region" {
  default = "ap-northeast-2"
}

variable "ami_id" {
  default = "ami-042e76978adeb8c48" # Ubuntu 22.04 서울 리전
}

variable "key_name" {
  description = "EC2 SSH 키페어 이름"
}

variable "my_ip" {
  description = "내 로컬 IP"
}

variable "db_allocated_storage" {
  description = "RDS 스토리지 크기 (GB)"
  default     = 20
}

variable "db_instance_class" {
  description = "RDS 인스턴스 클래스"
  default     = "db.t3.micro"
}

variable "db_engine" {
  description = "RDS 엔진"
  default     = "postgres"
}

variable "db_engine_version" {
  description = "RDS 엔진 버전"
  default     = "16.14"
}

variable "db_name" {
  description = "RDS 데이터베이스 이름"
  default     = "jobai"
}

variable "db_username" {
  description = "RDS 마스터 사용자 이름"
  default     = "jobai"
}

variable "db_password" {
  description = "RDS 마스터 비밀번호"
  type        = string
  sensitive   = true
}

variable "db_port" {
  description = "RDS 포트"
  default     = 5432
}
variable "db_backup_retention_period" {
  description = "RDS backup retention period in days. Use 0 for free-tier-limited initial deployment, 7 for production."
  type        = number
  default     = 0
}

variable "ecr_backend_repository_name" {
  description = "ECR repository name for the Spring Boot backend image"
  type        = string
  default     = "jobai-backend"
}

variable "ecr_ai_server_repository_name" {
  description = "ECR repository name for the AI server image"
  type        = string
  default     = "jobai-ai-server"
}

variable "github_oidc_thumbprint_list" {
  description = "Thumbprint list for the GitHub Actions OIDC provider"
  type        = list(string)
  default     = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

variable "github_actions_allowed_subjects" {
  description = "GitHub Actions OIDC subject claims allowed to assume the deploy role"
  type        = list(string)
  default = [
    "repo:jobai-project/jobai-backend:ref:refs/heads/develop",
    "repo:jobai-project/jobai-ai:ref:refs/heads/yeonjin/DLA",
    "repo:jobai-project/jobai-ai:ref:refs/heads/feat/add-ai-model-download"
  ]
}

variable "rds_port_forward_user_names" {
  description = "IAM user names allowed to open SSM port forwarding sessions to the backend EC2 for RDS access"
  type        = list(string)
  default     = []
}

variable "ai_server_dev_ips" {
  description = "AI 서버 8001 포트에 직접 접근할 개발자 공인 IP 목록. 임시 허용이며 작업이 끝나면 비운다."
  type        = list(string)
  default     = []
}

variable "monitoring_dev_ips" {
  description = "Grafana(3000)에 접근할 팀원 공인 IP 목록. 임시 허용이며 작업이 끝나면 비운다."
  type        = list(string)
  default     = []
}
