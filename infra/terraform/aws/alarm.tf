resource "aws_sns_topic" "alerts" {
  name = "payledger-alerts"
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# A released withdrawal ends in the Fail state, so a bank rejection (including the 666 demo) also triggers this.
resource "aws_cloudwatch_metric_alarm" "saga_failed" {
  alarm_name          = "payledger-withdrawal-failed"
  namespace           = "AWS/States"
  metric_name         = "ExecutionsFailed"
  dimensions          = { StateMachineArn = aws_sfn_state_machine.withdrawal.arn }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
