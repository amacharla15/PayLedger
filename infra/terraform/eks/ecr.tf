resource "aws_ecr_repository" "service" {
  for_each = toset(["ledger-service", "gateway", "notifier"])

  name = "payledger/${each.key}"
  # Lets terraform destroy delete the repositories even with images pushed.
  force_delete = true
}
