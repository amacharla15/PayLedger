# Apply infra/terraform/aws first: the ledger's role reuses that stack's least-privilege policy.
data "terraform_remote_state" "aws" {
  backend = "local"
  config = {
    path = "${path.module}/../aws/terraform.tfstate"
  }
}

# IRSA: the cluster's OIDC provider vouches for pods, and only pods running as the "ledger" service account in
# the "payledger" namespace may assume this role. The SDK in the pod picks the role up from the injected token.
resource "aws_iam_role" "ledger" {
  name = "${var.cluster_name}-ledger"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = module.eks.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${module.eks.oidc_provider}:sub" = "system:serviceaccount:payledger:ledger"
          "${module.eks.oidc_provider}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ledger" {
  role       = aws_iam_role.ledger.name
  policy_arn = data.terraform_remote_state.aws.outputs.ledger_policy_arn
}
