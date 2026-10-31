# Build the jar first: ./gradlew :bank-lambda:jar
locals {
  bank_jar = "${path.module}/../../../bank-lambda/build/libs/bank-lambda.jar"
}

resource "aws_cloudwatch_log_group" "bank" {
  name              = "/aws/lambda/payledger-bank"
  retention_in_days = 3
}

resource "aws_iam_role" "bank" {
  name = "payledger-bank-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# The function may only write to its own log group.
resource "aws_iam_role_policy" "bank_logs" {
  role = aws_iam_role.bank.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
      Resource = "${aws_cloudwatch_log_group.bank.arn}:*"
    }]
  })
}

resource "aws_lambda_function" "bank" {
  function_name    = "payledger-bank"
  role             = aws_iam_role.bank.arn
  runtime          = "java21"
  handler          = "com.akshith.payledger.bank.BankHandler::handleRequest"
  filename         = local.bank_jar
  source_code_hash = filebase64sha256(local.bank_jar)
  memory_size      = 512
  timeout          = 10

  depends_on = [aws_cloudwatch_log_group.bank, aws_iam_role_policy.bank_logs]
}
