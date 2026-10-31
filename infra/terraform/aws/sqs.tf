# The state machine puts SETTLE / RELEASE actions here; the ledger's WithdrawalWorker long-polls it.
resource "aws_sqs_queue" "withdrawal_actions" {
  name                       = "withdrawal-actions"
  visibility_timeout_seconds = 30
  receive_wait_time_seconds  = 20
  # Keep messages for 14 days so a ledger that was stopped can still settle or release later.
  message_retention_seconds = 1209600
}
