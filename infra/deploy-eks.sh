#!/usr/bin/env bash
# Pushes the images to ECR, fills the EKS overlay's placeholders with Terraform outputs and applies it.
# Run after both stacks are applied: infra/terraform/aws, then infra/terraform/eks.
set -euo pipefail
cd "$(dirname "$0")/.."

out() {
  terraform -chdir="infra/terraform/$1" output -raw "$2"
}

REGION=$(out eks region)
REGISTRY=$(out eks ecr_registry)
ROLE=$(out eks ledger_role_arn)
STATE_MACHINE=$(out aws state_machine_arn)
QUEUE=$(out aws withdrawal_queue_url)

aws eks update-kubeconfig --region "$REGION" --name "$(out eks cluster_name)"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

# The nodes are x86_64 (t3.medium), so build for linux/amd64 even on an Apple Silicon laptop.
for s in ledger-service gateway notifier; do
  docker build --platform linux/amd64 -f "$s/Dockerfile" -t "$REGISTRY/payledger/$s:latest" .
  docker push "$REGISTRY/payledger/$s:latest"
done

kubectl kustomize k8s/overlays/eks \
  | sed -e "s|ECR_REGISTRY|$REGISTRY|g" \
        -e "s|LEDGER_ROLE_ARN|$ROLE|g" \
        -e "s|AWS_REGION_VALUE|$REGION|g" \
        -e "s|STATE_MACHINE_ARN_VALUE|$STATE_MACHINE|g" \
        -e "s|WITHDRAWAL_QUEUE_URL_VALUE|$QUEUE|g" \
  | kubectl apply -f -

kubectl -n payledger wait --for=condition=available deployment --all --timeout=10m

HOST=""
while [ -z "$HOST" ]; do
  sleep 5
  HOST=$(kubectl -n payledger get svc gateway -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
done
echo "gateway: http://$HOST/graphql (the load balancer's DNS name can take a few minutes to resolve)"
