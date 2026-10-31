data "aws_availability_zones" "available" {
  state = "available"
}

# Public subnets only and no NAT gateway, to keep a short demo session cheap. Nodes get public IPs so they
# can reach the EKS API and ECR without NAT. Production would put nodes in private subnets behind NAT.
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.7"

  name = var.cluster_name
  cidr = "10.0.0.0/16"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)

  public_subnets          = ["10.0.0.0/20", "10.0.16.0/20"]
  map_public_ip_on_launch = true
  enable_nat_gateway      = false

  # Lets Kubernetes find these subnets when it creates the gateway's load balancer.
  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
}
