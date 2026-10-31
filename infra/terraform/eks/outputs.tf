output "region" {
  value = var.region
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "ecr_registry" {
  value = split("/", aws_ecr_repository.service["gateway"].repository_url)[0]
}

output "ledger_role_arn" {
  value = aws_iam_role.ledger.arn
}
