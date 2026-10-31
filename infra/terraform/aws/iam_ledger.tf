# Everything the ledger needs for the saga, and nothing else. Attach it to the IAM user whose keys you use
# for the local (Docker Compose) run; the EKS stack attaches the same policy to the ledger's IRSA role.
resource "aws_iam_policy" "ledger" {
  name = "payledger-ledger"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "StartSaga"
        Effect   = "Allow"
        Action   = "states:StartExecution"
        Resource = aws_sfn_state_machine.withdrawal.arn
      },
      {
        # SendTaskSuccess has no resource-level permissions; the task token itself identifies the execution.
        Sid      = "AnswerTaskTokens"
        Effect   = "Allow"
        Action   = "states:SendTaskSuccess"
        Resource = "*"
      },
      {
        Sid      = "ReadActions"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage"]
        Resource = aws_sqs_queue.withdrawal_actions.arn
      },
    ]
  })
}
