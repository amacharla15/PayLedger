resource "aws_cloudwatch_log_group" "saga" {
  name              = "/aws/vendedlogs/states/payledger-withdrawal"
  retention_in_days = 3
}

resource "aws_iam_role" "saga" {
  name = "payledger-withdrawal-saga"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "states.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "saga" {
  role = aws_iam_role.saga.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "CallBank"
        Effect   = "Allow"
        Action   = "lambda:InvokeFunction"
        Resource = [aws_lambda_function.bank.arn, "${aws_lambda_function.bank.arn}:*"]
      },
      {
        Sid      = "SendActions"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.withdrawal_actions.arn
      },
      {
        # Step Functions log delivery: these actions do not support resource-level permissions.
        Sid    = "DeliverLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogDelivery",
          "logs:GetLogDelivery",
          "logs:UpdateLogDelivery",
          "logs:DeleteLogDelivery",
          "logs:ListLogDeliveries",
          "logs:PutResourcePolicy",
          "logs:DescribeResourcePolicies",
          "logs:DescribeLogGroups",
        ]
        Resource = "*"
      },
    ]
  })
}

resource "aws_sfn_state_machine" "withdrawal" {
  name     = "payledger-withdrawal"
  role_arn = aws_iam_role.saga.arn
  definition = templatefile("${path.module}/../../../stepfunctions/withdrawal.asl.json", {
    bank_lambda_arn = aws_lambda_function.bank.arn
    queue_url       = aws_sqs_queue.withdrawal_actions.url
  })

  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.saga.arn}:*"
    include_execution_data = true
    level                  = "ERROR"
  }

  depends_on = [aws_iam_role_policy.saga]
}
