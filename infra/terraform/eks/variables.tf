variable "region" {
  type    = string
  default = "us-west-2"
}

variable "cluster_name" {
  type    = string
  default = "payledger"
}

# In EKS standard support until August 2027. Versions in extended support cost six times as much per hour.
variable "kubernetes_version" {
  type    = string
  default = "1.36"
}
