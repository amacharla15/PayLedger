output "region" {
  value = var.region
}

output "state_machine_arn" {
  value = aws_sfn_state_machine.withdrawal.arn
}

output "withdrawal_queue_url" {
  value = aws_sqs_queue.withdrawal_actions.url
}

output "ledger_policy_arn" {
  value = aws_iam_policy.ledger.arn
}
