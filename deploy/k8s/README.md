# Plain Kubernetes manifests

`nexora.yaml` is the [Helm chart](../helm/nexora)'s default rendering, for teams that apply YAML with
`kubectl` or a GitOps tool instead of Helm. It is generated, so do not edit it by hand: change the chart
and run `./generate.sh`. CI fails when the two disagree.

```bash
kubectl apply -f namespace.yaml
kubectl --namespace nexora create secret generic nexora-api-key \
  --from-literal=api-key="$(openssl rand -base64 36)"
kubectl --namespace nexora apply -f nexora.yaml
kubectl --namespace nexora port-forward svc/nexora 9464 9465
```

The manifests carry no `metadata.namespace`, so apply them with `--namespace`. `namespace.yaml`
enforces the `restricted` Pod Security standard, which the pod meets.

What you get: one pod of `ghcr.io/iamvirul/nexora` at the chart's `appVersion` (published from the 0.5.0
release on; before that, change `image:` to an image you built), an embedded H2 store on
a 1Gi PersistentVolumeClaim, probes on `/health/live` and `/health/ready`, requests `250m` / `512Mi`
and limits `1` CPU / `1Gi`. For PostgreSQL, HTTPS, monitoring, autoscaling or more replicas, use the
chart, or render it with your own values:

```bash
helm template nexora ../helm/nexora --namespace nexora -f my-values.yaml > nexora.yaml
```
