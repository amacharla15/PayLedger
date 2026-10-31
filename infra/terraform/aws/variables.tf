variable "region" {
  type    = string
  default = "us-west-2"
}

variable "alert_email" {
  type        = string
  description = "Receives the budget alerts and the failed-withdrawal alarm. Confirm the SNS subscription email after apply."
}
